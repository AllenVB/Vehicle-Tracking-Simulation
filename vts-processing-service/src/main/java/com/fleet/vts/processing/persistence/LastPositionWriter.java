package com.fleet.vts.processing.persistence;

import com.fleet.vts.common.event.TelemetryEvent;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Batch UPSERT of the one-row-per-vehicle last position. The guard keeps the
 * newest reading when out-of-order events arrive. Callers pass at most one
 * (latest) event per vehicle.
 */
@Component
public class LastPositionWriter {

    private static final String SQL = """
            INSERT INTO vehicle_last_position
                (vehicle_id, tenant_id, ts, location, speed_kmh, heading, engine_on, ignition, updated_at)
            VALUES (?, ?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?, ?, ?, ?, now())
            ON CONFLICT (vehicle_id) DO UPDATE SET
                tenant_id  = EXCLUDED.tenant_id,
                ts         = EXCLUDED.ts,
                location   = EXCLUDED.location,
                speed_kmh  = EXCLUDED.speed_kmh,
                heading    = EXCLUDED.heading,
                engine_on  = EXCLUDED.engine_on,
                ignition   = EXCLUDED.ignition,
                updated_at = now()
            WHERE vehicle_last_position.ts <= EXCLUDED.ts
            """;

    private final JdbcTemplate jdbc;

    public LastPositionWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void upsertBatch(List<TelemetryEvent> latestPerVehicle) {
        if (latestPerVehicle.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                TelemetryEvent e = latestPerVehicle.get(i);
                ps.setObject(1, e.vehicleId());
                ps.setObject(2, e.tenantId());
                ps.setObject(3, e.ts() == null ? null : e.ts().atOffset(ZoneOffset.UTC));
                ps.setObject(4, e.lon());
                ps.setObject(5, e.lat());
                ps.setObject(6, e.speedKmh());
                ps.setObject(7, e.heading());
                ps.setObject(8, e.engineOn());
                ps.setObject(9, e.ignition());
            }

            @Override
            public int getBatchSize() {
                return latestPerVehicle.size();
            }
        });
    }
}
