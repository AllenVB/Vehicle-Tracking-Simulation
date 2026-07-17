package com.fleet.vts.analytics.rules;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Answers, for a vehicle in the stream, which rules apply to it and at what threshold.
 *
 * <p>Replaces the pair of registries this used to need — one that knew which vehicles were
 * helicopters, one that knew their speed limit. Both encoded the same underlying fact
 * (a vehicle's type decides its rules) and each answered only half of it, which is how the
 * topology ended up exempting helicopters from harsh braking but not from idling.
 *
 * <p>Applicability and thresholds both come from {@code rule_assignment} rows scoped to
 * {@code VEHICLE_TYPE} — the same rows the processing service reads. The two engines run
 * different code, but they can no longer disagree about *policy*, which is what they
 * actually diverged on.
 *
 * <p>Loaded per (rule, vehicle) rather than per (rule, type) so the hot path is one map
 * lookup: the topology asks this question for every reading, and the fleet is static
 * enough to re-resolve on a timer.
 */
@Component
public class VehicleRuleRegistry {

    private static final Logger log = LoggerFactory.getLogger(VehicleRuleRegistry.class);

    /** Used when a vehicle has no resolvable limit; never guess high. */
    public static final double FALLBACK_SPEED_LIMIT = 80.0;

    /** (ruleCode, vehicleId) -> effective threshold. Absence means the rule does not apply. */
    private volatile Map<String, Double> thresholds = Map.of();

    private final JdbcTemplate jdbc;

    @Autowired
    public VehicleRuleRegistry(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Test constructor: preloaded state, no database. Keys are {@code ruleCode + ":" +
     * vehicleId}; a key's absence means the rule does not apply to that vehicle.
     */
    public VehicleRuleRegistry(Map<String, Double> thresholds) {
        this.jdbc = null;
        this.thresholds = Map.copyOf(thresholds);
    }

    @PostConstruct
    @Scheduled(fixedDelay = 300_000)   // the fleet is static, but rules can change
    public void load() {
        if (jdbc == null) {
            return;
        }
        Map<String, Double> loaded = new HashMap<>();
        // A VEHICLE_TYPE assignment with enabled = false removes the rule for that type;
        // one with a threshold_override retunes it. A type with no row keeps the rule's
        // own default, so only differences are stored.
        jdbc.query("""
                SELECT r.code AS rule_code, v.id AS vehicle_id,
                       COALESCE(ra.threshold_override, r.threshold_value) AS threshold
                FROM vehicle v
                JOIN rule r ON r.tenant_id = v.tenant_id
                LEFT JOIN rule_assignment ra
                       ON ra.rule_id = r.id
                      AND ra.scope_type = 'VEHICLE_TYPE'
                      AND ra.scope_code = v.type
                WHERE r.enabled = true
                  AND COALESCE(ra.enabled, true) = true
                """, rs -> {
            Number threshold = (Number) rs.getObject("threshold");
            loaded.put(key(rs.getString("rule_code"), rs.getString("vehicle_id")),
                    threshold == null ? Double.NaN : threshold.doubleValue());
        });
        this.thresholds = Map.copyOf(loaded);
        log.info("Rule applicability resolved: {} (rule, vehicle) pairs", loaded.size());
    }

    /** True when {@code ruleCode} applies to this vehicle's type. */
    public boolean applies(String ruleCode, String vehicleId) {
        return thresholds.containsKey(key(ruleCode, vehicleId));
    }

    /**
     * The effective threshold, or {@code fallback} when the rule does not apply to this
     * vehicle or carries no threshold of its own (idling and geofences are windowed, not
     * thresholded).
     */
    public double threshold(String ruleCode, String vehicleId, double fallback) {
        Double value = thresholds.get(key(ruleCode, vehicleId));
        return value == null || value.isNaN() ? fallback : value;
    }

    /** The SPEED_LIMIT for a vehicle — car 110, motorcycle 90, truck 80. */
    public double speedLimit(String vehicleId) {
        return threshold("SPEED_LIMIT", vehicleId, FALLBACK_SPEED_LIMIT);
    }

    private static String key(String ruleCode, String vehicleId) {
        return ruleCode + ":" + vehicleId;
    }
}
