package com.fleet.vts.gateway.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
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

    /** The vehicle's last 20 messages, newest first. */
    public List<Map<String, Object>> recent(long tenantId, long vehicleId) {
        return jdbc.query("""
                        SELECT category, body, created_at
                        FROM vehicle_message
                        WHERE tenant_id = ? AND vehicle_id = ?
                        ORDER BY created_at DESC LIMIT 20
                        """,
                (rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("category", rs.getString("category"));
                    m.put("body", rs.getString("body"));
                    m.put("at", rs.getObject("created_at", OffsetDateTime.class).toInstant().toString());
                    return m;
                },
                tenantId, vehicleId);
    }

    /** Type codes the vehicle taxonomy actually defines — used to reject a bad vehicle type. */
    public List<String> validVehicleTypes() {
        return new ArrayList<>(jdbc.queryForList("SELECT code FROM vehicle_type", String.class));
    }
}
