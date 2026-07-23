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
 * <p>The write follows the latest reading, not a running maximum. A monotonic guard
 * ({@code odometer_km < new}) was tried first and it froze: the simulator is the source of
 * truth for a vehicle's mileage, and it re-bases each vehicle's odometer to a fixed per-vehicle
 * value on restart. That fresh base sits just below the peak the previous run left in the
 * database, so a strict guard rejected every reading and the mileage — and the maintenance
 * counter built on it — stopped moving with no error anywhere.
 *
 * <p>So the DB simply mirrors the device's current odometer. {@code latestPerVehicle} is the
 * newest reading in the batch and Kafka keeps a vehicle's telemetry ordered, so successive
 * writes climb in normal operation. A store-and-forward burst can momentarily write an older
 * odometer, which the next live reading corrects — acceptable for a mileage counter, unlike for
 * a legal odometer, where a timestamp-guarded write would be the next step.
 */
@Component
public class OdometerWriter {

    private static final String SQL = """
            UPDATE vehicle
               SET odometer_km = ?, updated_at = now()
             WHERE id = ?
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
            }

            @Override
            public int getBatchSize() {
                return withOdometer.size();
            }
        });
    }
}
