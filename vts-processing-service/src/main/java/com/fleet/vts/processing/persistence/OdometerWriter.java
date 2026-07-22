package com.fleet.vts.processing.persistence;

import com.fleet.vts.common.event.TelemetryEvent;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps {@code vehicle.odometer_km} current from the device's own odometer.
 *
 * <p>The column has existed since V3 and nothing ever wrote to it. Every reading carried an
 * odometer into the {@code telemetry} hypertable, but the vehicle row kept whatever the seed
 * put there — so anything asking "how far has this vehicle been driven" got an answer frozen
 * at install. The maintenance reminder is exactly that question, which is why it counted zero
 * vehicles due every night without ever failing.
 *
 * <p>The update is monotonic. An odometer does not run backwards, and a device coming back
 * from a coverage gap delivers readings that are hours old — accepting one of those would
 * roll the fleet's mileage back and, with it, push every service date into the future.
 */
@Component
public class OdometerWriter {

    private static final String SQL = """
            UPDATE vehicle
               SET odometer_km = ?, updated_at = now()
             WHERE id = ? AND odometer_km < ?
            """;

    private final JdbcTemplate jdbc;

    public OdometerWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void updateBatch(List<TelemetryEvent> latestPerVehicle) {
        List<TelemetryEvent> withOdometer = new ArrayList<>(latestPerVehicle.size());
        for (TelemetryEvent e : latestPerVehicle) {
            if (e.odometerKm() != null && e.vehicleId() != null) {
                withOdometer.add(e);
            }
        }
        if (withOdometer.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                TelemetryEvent e = withOdometer.get(i);
                ps.setLong(1, e.odometerKm());
                ps.setLong(2, e.vehicleId());
                ps.setLong(3, e.odometerKm());
            }

            @Override
            public int getBatchSize() {
                return withOdometer.size();
            }
        });
    }
}
