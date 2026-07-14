package com.fleet.vts.analytics.rules;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * The effective SPEED_LIMIT threshold per vehicle, resolved exactly like the stateless
 * engine does: the rule's own value, overridden by a GROUP-scoped {@code rule_assignment}
 * (cars 110, motorcycles 90, trucks 80).
 *
 * <p>Without this the stream topology hard-coded 80 km/h for SUSTAINED_SPEEDING, so a car
 * cruising at 100 was flagged even though its limit is 110 — thresholds have to come from
 * the rule tables, not from the code.
 */
@Component
public class SpeedLimitRegistry {

    private static final Logger log = LoggerFactory.getLogger(SpeedLimitRegistry.class);
    /** Used when a vehicle has no rule row (should not happen, but never guess high). */
    public static final double FALLBACK_LIMIT = 80.0;

    private final JdbcTemplate jdbc;
    private volatile Map<Long, Double> byVehicle = Map.of();

    @org.springframework.beans.factory.annotation.Autowired
    public SpeedLimitRegistry(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Test constructor: preloaded limits, no database. */
    public SpeedLimitRegistry(Map<Long, Double> limits) {
        this.jdbc = null;
        this.byVehicle = Map.copyOf(limits);
    }

    @PostConstruct
    @Scheduled(fixedDelay = 300_000)   // rules can change; re-resolve every 5 minutes
    public void load() {
        if (jdbc == null) {
            return;
        }
        Map<Long, Double> loaded = new HashMap<>();
        jdbc.query("""
                SELECT v.id AS vehicle_id,
                       COALESCE(ra.threshold_override, r.threshold_value) AS threshold
                FROM vehicle v
                JOIN rule r ON r.tenant_id = v.tenant_id AND r.code = 'SPEED_LIMIT'
                LEFT JOIN rule_assignment ra
                       ON ra.rule_id = r.id AND ra.scope_type = 'GROUP'
                      AND ra.scope_id = v.group_id AND ra.enabled = true
                """, rs -> {
            Number thr = (Number) rs.getObject("threshold");
            if (thr != null) {
                loaded.put(rs.getLong("vehicle_id"), thr.doubleValue());
            }
        });
        this.byVehicle = Map.copyOf(loaded);
        log.info("Speed limits resolved for {} vehicles", loaded.size());
    }

    /** The limit for a vehicle; the stream is keyed by vehicleId as a string. */
    public double forVehicle(String vehicleId) {
        try {
            return byVehicle.getOrDefault(Long.valueOf(vehicleId), FALLBACK_LIMIT);
        } catch (NumberFormatException e) {
            return FALLBACK_LIMIT;
        }
    }
}
