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
 * Bulk-writes telemetry into the TimescaleDB hypertable with a single
 * {@code JdbcTemplate.batchUpdate()} and {@code ON CONFLICT DO NOTHING}. This is
 * the only supported write path for telemetry — never per-row saves, never JPA.
 */
@Component
public class TelemetryWriter {

    private static final String SQL = """
            INSERT INTO telemetry
                (tenant_id, vehicle_id, device_id, ts, location,
                 speed_kmh, heading, battery, fuel_pct, engine_on, ignition, odometer_km)
            VALUES (?, ?, ?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                    ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (vehicle_id, ts) DO NOTHING
            """;

    private final JdbcTemplate jdbc;

    public TelemetryWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertBatch(List<TelemetryEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                TelemetryEvent e = events.get(i);
                ps.setObject(1, e.tenantId());
                ps.setObject(2, e.vehicleId());
                ps.setObject(3, e.deviceId());
                ps.setObject(4, e.ts() == null ? null : e.ts().atOffset(ZoneOffset.UTC));
                ps.setObject(5, e.lon());   // ST_MakePoint(x = lon, y = lat)
                ps.setObject(6, e.lat());
                ps.setObject(7, e.speedKmh());
                ps.setObject(8, e.heading());
                ps.setObject(9, e.battery());
                ps.setObject(10, e.fuelPct());
                ps.setObject(11, e.engineOn());
                ps.setObject(12, e.ignition());
                ps.setObject(13, e.odometerKm());
            }

            @Override
            public int getBatchSize() {
                return events.size();
            }
        });
    }
}
