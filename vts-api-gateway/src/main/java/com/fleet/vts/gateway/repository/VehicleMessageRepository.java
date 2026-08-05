package com.fleet.vts.gateway.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQL for operator → vehicle messages.
 *
 * <p>Extracted from {@code VehicleMessageController}, which held five raw {@code jdbc} calls
 * inline. The SQL is not the controller's concern — the controller decides what an endpoint
 * accepts and returns; where the rows live is this class's job. Keeping them together made the
 * controller the one place that both parsed HTTP and wrote SQL, which is the leak this closes.
 */
@Repository
public class VehicleMessageRepository {

    private final JdbcTemplate jdbc;

    public VehicleMessageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The vehicle's plate, or {@code null} if it is not this tenant's — the 404 signal. */
    public String findPlate(long vehicleId, long tenantId) {
        return jdbc.query("SELECT plate FROM vehicle WHERE id = ? AND tenant_id = ?",
                (ResultSetExtractor<String>) rs -> rs.next() ? rs.getString(1) : null,
                vehicleId, tenantId);
    }

    public void insert(long tenantId, long vehicleId, String category, String body) {
        jdbc.update("INSERT INTO vehicle_message (tenant_id, vehicle_id, category, body) VALUES (?, ?, ?, ?)",
                tenantId, vehicleId, category, body);
    }

    /** The vehicle's last 50 messages (both directions), oldest first — for the support chat panel. */
    public List<Map<String, Object>> recent(long tenantId, long vehicleId) {
        return jdbc.query("""
                        SELECT category, body, direction, audio_ref, created_at FROM (
                            SELECT category, body, direction, audio_ref, created_at
                            FROM vehicle_message
                            WHERE tenant_id = ? AND vehicle_id = ?
                            ORDER BY created_at DESC LIMIT 50
                        ) t ORDER BY created_at ASC
                        """,
                (rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("category", rs.getString("category"));
                    m.put("body", rs.getString("body"));
                    m.put("audio", rs.getString("audio_ref"));
                    m.put("direction", rs.getString("direction"));
                    m.put("at", rs.getObject("created_at", OffsetDateTime.class).toInstant().toString());
                    return m;
                },
                tenantId, vehicleId);
    }

    /** The vehicle's plate for a device already authenticated to it (no tenant needed). */
    public String plateOf(long vehicleId) {
        return jdbc.query("SELECT plate FROM vehicle WHERE id = ?",
                (ResultSetExtractor<String>) rs -> rs.next() ? rs.getString(1) : null, vehicleId);
    }

    /**
     * Undelivered operator→driver messages, claimed atomically. The {@code delivered_at IS NULL}
     * predicate is in the UPDATE's own WHERE (not a subquery), so under READ COMMITTED a second
     * concurrent poll re-evaluates each row after the first commits, sees it is no longer NULL and
     * skips it — a message is therefore returned to the phone exactly once, even if several polls
     * overlap. Ordering is applied by the caller (by {@code at}).
     */
    public List<Map<String, Object>> pullForDevice(long vehicleId) {
        return jdbc.query("""
                        UPDATE vehicle_message SET delivered_at = now()
                        WHERE vehicle_id = ? AND direction = 'TO_DRIVER' AND delivered_at IS NULL
                        RETURNING category, body, audio_ref, created_at
                        """,
                (rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("category", rs.getString("category"));
                    m.put("body", rs.getString("body"));
                    m.put("audio", rs.getString("audio_ref"));
                    m.put("at", rs.getObject("created_at", OffsetDateTime.class).toInstant().toString());
                    return m;
                },
                vehicleId);
    }

    /**
     * The vehicle's full conversation (both directions, last 50, oldest first) for the DRIVER app's
     * history — session-scoped, so no tenant filter. Does not mark anything delivered; the caller
     * marks the operator messages seen separately.
     */
    public List<Map<String, Object>> conversation(long vehicleId) {
        return jdbc.query("""
                        SELECT category, body, direction, audio_ref, created_at FROM (
                            SELECT category, body, direction, audio_ref, created_at
                            FROM vehicle_message
                            WHERE vehicle_id = ?
                            ORDER BY created_at DESC LIMIT 50
                        ) t ORDER BY created_at ASC
                        """,
                (rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("category", rs.getString("category"));
                    m.put("body", rs.getString("body"));
                    m.put("audio", rs.getString("audio_ref"));
                    m.put("direction", rs.getString("direction"));
                    m.put("at", rs.getObject("created_at", OffsetDateTime.class).toInstant().toString());
                    return m;
                },
                vehicleId);
    }

    /** Mark all undelivered operator→driver messages as seen (used when the driver loads history). */
    public void markDelivered(long vehicleId) {
        jdbc.update("UPDATE vehicle_message SET delivered_at = now() "
                + "WHERE vehicle_id = ? AND direction = 'TO_DRIVER' AND delivered_at IS NULL", vehicleId);
    }

    /** A driver's one-tap reply, stored FROM_DRIVER (and already "delivered", it is outbound). */
    public void insertDeviceReply(long vehicleId, String category, String body) {
        jdbc.update("""
                        INSERT INTO vehicle_message (tenant_id, vehicle_id, category, body, direction, delivered_at)
                        SELECT tenant_id, id, ?, ?, 'FROM_DRIVER', now() FROM vehicle WHERE id = ?
                        """,
                category, body, vehicleId);
    }

    /** Operator→driver voice message (TO_DRIVER, undelivered so the driver's poll picks it up). */
    public void insertOperatorAudio(long tenantId, long vehicleId, String body, String audioRef, String audioType) {
        jdbc.update("""
                        INSERT INTO vehicle_message (tenant_id, vehicle_id, category, body, audio_ref, audio_type)
                        VALUES (?, ?, 'SES', ?, ?, ?)
                        """,
                tenantId, vehicleId, body, audioRef, audioType);
    }

    /** Driver→operator voice message (FROM_DRIVER, outbound so already delivered). */
    public void insertDeviceAudio(long vehicleId, String body, String audioRef, String audioType) {
        jdbc.update("""
                        INSERT INTO vehicle_message (tenant_id, vehicle_id, category, body, direction, delivered_at, audio_ref, audio_type)
                        SELECT tenant_id, id, 'SES', ?, 'FROM_DRIVER', now(), ?, ? FROM vehicle WHERE id = ?
                        """,
                body, audioRef, audioType, vehicleId);
    }

    /** Content type stored for an audio ref, or null if the ref is unknown (the serving 404 signal). */
    public String audioTypeOf(String audioRef) {
        return jdbc.query("SELECT audio_type FROM vehicle_message WHERE audio_ref = ? LIMIT 1",
                (ResultSetExtractor<String>) rs -> rs.next() ? rs.getString(1) : null, audioRef);
    }

    /** Audio refs of messages older than the retention window — their files must be deleted. */
    public List<String> audioRefsOlderThan(int days) {
        return jdbc.queryForList(
                "SELECT audio_ref FROM vehicle_message "
                        + "WHERE audio_ref IS NOT NULL AND created_at < now() - make_interval(days => ?)",
                String.class, days);
    }

    /** Delete all messages older than the retention window; returns how many rows went. */
    public int deleteOlderThan(int days) {
        return jdbc.update("DELETE FROM vehicle_message WHERE created_at < now() - make_interval(days => ?)", days);
    }
}
