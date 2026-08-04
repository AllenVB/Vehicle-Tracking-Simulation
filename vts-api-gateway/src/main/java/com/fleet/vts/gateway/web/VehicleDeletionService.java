package com.fleet.vts.gateway.web;

import com.fleet.vts.gateway.track.DriverSessionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hard-deletes a vehicle and every row that references it. The schema's foreign keys are NO ACTION
 * (so deleting a vehicle with any history would otherwise fail), so children are removed in
 * dependency order within one transaction, then the vehicle. Time-series rows (telemetry,
 * violation) carry a {@code vehicle_id} but no FK; they are cleared here too so nothing is
 * orphaned. The vehicle's active driver session (Redis) is freed last.
 *
 * <p>Continuous aggregates ({@code telemetry_1min/hourly}, {@code violation_daily_summary}) are
 * materialized views over the base tables and refresh on their own schedule — not deleted here.
 */
@Service
public class VehicleDeletionService {

    private final JdbcTemplate jdbc;
    private final DriverSessionService sessions;

    public VehicleDeletionService(JdbcTemplate jdbc, DriverSessionService sessions) {
        this.jdbc = jdbc;
        this.sessions = sessions;
    }

    @Transactional
    public void purge(long vehicleId) {
        // Deepest children first (grandchildren of the vehicle).
        del("DELETE FROM notification_delivery_attempt WHERE notification_id IN (SELECT id FROM notification WHERE vehicle_id = ?)", vehicleId);
        del("DELETE FROM device_heartbeat WHERE device_id IN (SELECT id FROM device WHERE vehicle_id = ?)", vehicleId);
        del("DELETE FROM sim_card WHERE device_id IN (SELECT id FROM device WHERE vehicle_id = ?)", vehicleId);
        del("DELETE FROM trip_point WHERE trip_id IN (SELECT id FROM trip WHERE vehicle_id = ?)", vehicleId);
        del("DELETE FROM device_command WHERE vehicle_id = ?", vehicleId);
        del("DELETE FROM stop_event WHERE vehicle_id = ?", vehicleId);
        del("DELETE FROM maintenance_record WHERE vehicle_id = ?", vehicleId);

        // Mid-level children.
        del("DELETE FROM device WHERE vehicle_id = ?", vehicleId);
        del("DELETE FROM trip WHERE vehicle_id = ?", vehicleId);
        del("DELETE FROM maintenance_plan WHERE vehicle_id = ?", vehicleId);
        del("DELETE FROM notification WHERE vehicle_id = ?", vehicleId);

        // Remaining direct children of vehicle.
        del("DELETE FROM driver_login WHERE vehicle_id = ?", vehicleId);
        del("DELETE FROM fuel_event WHERE vehicle_id = ?", vehicleId);
        del("DELETE FROM geofence_event WHERE vehicle_id = ?", vehicleId);
        del("DELETE FROM vehicle_driver_assignment WHERE vehicle_id = ?", vehicleId);
        del("DELETE FROM vehicle_last_position WHERE vehicle_id = ?", vehicleId);
        del("DELETE FROM vehicle_message WHERE vehicle_id = ?", vehicleId);

        // FK-less time-series (hypertables).
        del("DELETE FROM violation WHERE vehicle_id = ?", vehicleId);
        del("DELETE FROM telemetry WHERE vehicle_id = ?", vehicleId);

        // The vehicle itself.
        del("DELETE FROM vehicle WHERE id = ?", vehicleId);

        sessions.evict(vehicleId);
    }

    private void del(String sql, long vehicleId) {
        jdbc.update(sql, vehicleId);
    }
}
