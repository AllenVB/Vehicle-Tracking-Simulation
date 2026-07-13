package com.fleet.vts.processing.rules;

import com.fleet.vts.common.event.TelemetryEvent;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Builds the {@link VehicleContext} for a reading: attributes the driver via the
 * open temporal assignment (cached) and pulls the effective rules. Using the
 * assignment (not vehicle.current_driver_id) attributes violations to whoever
 * actually held the vehicle at that time.
 */
@Service
public class VehicleContextResolver {

    private final JdbcTemplate jdbc;
    private final RuleConfigService ruleConfig;
    private final Cache<Long, Optional<Long>> driverByVehicle = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofSeconds(60))
            .build();

    public VehicleContextResolver(JdbcTemplate jdbc, RuleConfigService ruleConfig) {
        this.jdbc = jdbc;
        this.ruleConfig = ruleConfig;
    }

    public VehicleContext resolve(TelemetryEvent event) {
        Long driverId = driverByVehicle.get(event.vehicleId(), this::loadDriver).orElse(null);
        return new VehicleContext(
                event.vehicleId(),
                event.tenantId(),
                driverId,
                event.deviceId(),
                ruleConfig.rulesFor(event.tenantId(), event.vehicleId()));
    }

    private Optional<Long> loadDriver(Long vehicleId) {
        try {
            Long driverId = jdbc.queryForObject(
                    "SELECT driver_id FROM vehicle_driver_assignment "
                            + "WHERE vehicle_id = ? AND released_at IS NULL "
                            + "ORDER BY assigned_at DESC LIMIT 1",
                    Long.class, vehicleId);
            return Optional.ofNullable(driverId);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }
}
