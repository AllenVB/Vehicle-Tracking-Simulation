package com.fleet.vts.gateway.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQL for admin broadcast messages (the pub/sub announcement channel). Kept separate from
 * {@link VehicleMessageRepository} because a broadcast is not tied to a vehicle or a driver —
 * it is a tenant-wide announcement fanned out to every connected operator panel.
 */
@Repository
public class BroadcastMessageRepository {

    /** How many past announcements the notification panel shows on load. */
    private static final int HISTORY_LIMIT = 50;

    /** id/sender/title/body/at — the shape both operator and driver clients consume. */
    private static final RowMapper<Map<String, Object>> ROW = (rs, n) -> {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getLong("id"));
        m.put("sender", rs.getString("sender"));
        m.put("title", rs.getString("title"));
        m.put("body", rs.getString("body"));
        m.put("at", rs.getObject("created_at", OffsetDateTime.class).toInstant().toString());
        return m;
    };

    private final JdbcTemplate jdbc;

    public BroadcastMessageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Stores a broadcast and returns its generated id. */
    public long insert(long tenantId, String sender, String title, String body) {
        return jdbc.queryForObject("""
                        INSERT INTO broadcast_message (tenant_id, sender, title, body)
                        VALUES (?, ?, ?, ?)
                        RETURNING id
                        """,
                Long.class, tenantId, sender, title, body);
    }

    /** The tenant's most recent announcements, newest first — for the operator notification panel. */
    public List<Map<String, Object>> recent(long tenantId) {
        return jdbc.query("""
                        SELECT id, sender, title, body, created_at
                        FROM broadcast_message
                        WHERE tenant_id = ?
                        ORDER BY created_at DESC
                        LIMIT %d
                        """.formatted(HISTORY_LIMIT),
                ROW, tenantId);
    }

    /**
     * The most recent announcements for the tenant that owns {@code vehicleId}, newest first — for
     * the driver phone, which is identified by its vehicle (device session) rather than a JWT tenant.
     */
    public List<Map<String, Object>> recentForVehicle(long vehicleId) {
        return jdbc.query("""
                        SELECT b.id, b.sender, b.title, b.body, b.created_at
                        FROM broadcast_message b
                        JOIN vehicle v ON v.tenant_id = b.tenant_id
                        WHERE v.id = ?
                        ORDER BY b.created_at DESC
                        LIMIT %d
                        """.formatted(HISTORY_LIMIT),
                ROW, vehicleId);
    }
}
