package com.fleet.vts.processing.rules;

import com.fleet.vts.common.enums.RuleType;
import com.fleet.vts.common.enums.Severity;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves the effective rules for a vehicle: which ones apply to it, and at what
 * threshold. Neither is hard-coded — both come from the {@code rule} table with
 * {@code rule_assignment} overrides applied. Everything is cached in Caffeine (TTL 60s)
 * and can be flushed on a rule-change invalidation event.
 *
 * <p>Applicability is resolved from the vehicle's <em>type</em>. This used to be a
 * hard-coded set holding the single entry {@code SPEED_LIMIT}, skipped for vehicles whose
 * type happened to read {@code HELICOPTER} — so helicopters, exempt from speed limits,
 * still collected idling and road-shaped violations, and the stream topology maintained
 * its own differently-wrong copy of the same idea. Both engines now read the same
 * {@code VEHICLE_TYPE}-scoped rows.
 */
@Service
public class RuleConfigService {

    private record RuleDef(Long ruleId, String code, RuleType type, Severity severity,
                           double thresholdDefault, boolean enabled, int cooldownSeconds) {
    }

    /**
     * What a {@code VEHICLE_TYPE}-scoped assignment says about one (rule, type) pair:
     * whether the rule applies at all, and the threshold to use if it does
     * ({@code null} = keep the rule's own default).
     */
    private record TypeAssignment(Double thresholdOverride, boolean enabled) {
    }

    private final JdbcTemplate jdbc;

    private final Cache<Long, Map<String, RuleDef>> rulesByTenant = build();
    private final Cache<Long, Map<String, Map<Long, Double>>> groupOverridesByTenant = build();
    private final Cache<Long, Map<String, Map<String, TypeAssignment>>> typeAssignmentsByTenant = build();
    private final Cache<Long, Optional<Long>> vehicleGroup = build();
    private final Cache<Long, Optional<String>> vehicleType = build();

    public RuleConfigService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static <K, V> Cache<K, V> build() {
        return Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofSeconds(60))
                .build();
    }

    /**
     * Effective rules for a vehicle, keyed by rule code. A rule the vehicle's type is
     * exempt from is absent from the result entirely, so callers cannot evaluate it.
     *
     * <p>Threshold precedence, narrowest last: the rule's default, then the type override
     * (what this kind of vehicle warrants), then the group override. Group wins because it
     * is a deliberate choice about specific vehicles, whereas the type override is the
     * baseline for every vehicle of that kind.
     */
    public Map<String, RuleView> rulesFor(Long tenantId, Long vehicleId) {
        Map<String, RuleDef> defs = rulesByTenant.get(tenantId, this::loadRules);
        Map<String, Map<Long, Double>> groupOverrides =
                groupOverridesByTenant.get(tenantId, this::loadGroupOverrides);
        Map<String, Map<String, TypeAssignment>> typeAssignments =
                typeAssignmentsByTenant.get(tenantId, this::loadTypeAssignments);
        Long groupId = vehicleGroup.get(vehicleId, this::loadVehicleGroup).orElse(null);
        String type = vehicleType.get(vehicleId, this::loadVehicleType).orElse(null);

        Map<String, RuleView> result = new LinkedHashMap<>();
        for (RuleDef def : defs.values()) {
            TypeAssignment forType = type == null
                    ? null
                    : typeAssignments.getOrDefault(def.code(), Map.of()).get(type);
            if (forType != null && !forType.enabled()) {
                continue;   // this rule does not apply to this kind of vehicle
            }

            double threshold = def.thresholdDefault();
            if (forType != null && forType.thresholdOverride() != null) {
                threshold = forType.thresholdOverride();
            }
            if (groupId != null) {
                Double override = groupOverrides.getOrDefault(def.code(), Map.of()).get(groupId);
                if (override != null) {
                    threshold = override;
                }
            }
            result.put(def.code(), new RuleView(def.ruleId(), def.code(), def.type(),
                    def.severity(), threshold, def.enabled(), def.cooldownSeconds()));
        }
        return result;
    }

    /** Flush all caches (invoked when a rule changes). */
    public void invalidateAll() {
        rulesByTenant.invalidateAll();
        groupOverridesByTenant.invalidateAll();
        typeAssignmentsByTenant.invalidateAll();
        vehicleGroup.invalidateAll();
        vehicleType.invalidateAll();
    }

    private Optional<String> loadVehicleType(Long vehicleId) {
        return jdbc.query("SELECT type FROM vehicle WHERE id = ?",
                (ResultSetExtractor<Optional<String>>) rs ->
                        rs.next() ? Optional.ofNullable(rs.getString("type")) : Optional.empty(),
                vehicleId);
    }

    /** rule code -> vehicle type -> what that type's assignment says. */
    private Map<String, Map<String, TypeAssignment>> loadTypeAssignments(Long tenantId) {
        Map<String, Map<String, TypeAssignment>> map = new HashMap<>();
        jdbc.query("SELECT r.code AS code, ra.scope_code AS vehicle_type, "
                        + "ra.threshold_override AS thr, ra.enabled AS enabled "
                        + "FROM rule_assignment ra JOIN rule r ON r.id = ra.rule_id "
                        + "WHERE ra.tenant_id = ? AND ra.scope_type = 'VEHICLE_TYPE'",
                rs -> {
                    Number threshold = (Number) rs.getObject("thr");
                    map.computeIfAbsent(rs.getString("code"), k -> new HashMap<>())
                            .put(rs.getString("vehicle_type"), new TypeAssignment(
                                    threshold == null ? null : threshold.doubleValue(),
                                    rs.getBoolean("enabled")));
                },
                tenantId);
        return map;
    }

    private Map<String, RuleDef> loadRules(Long tenantId) {
        Map<String, RuleDef> map = new HashMap<>();
        jdbc.query("SELECT id, code, type, severity, threshold_value, enabled, cooldown_seconds "
                        + "FROM rule WHERE tenant_id = ?",
                rs -> {
                    Number threshold = (Number) rs.getObject("threshold_value");
                    map.put(rs.getString("code"), new RuleDef(
                            rs.getLong("id"),
                            rs.getString("code"),
                            RuleType.valueOf(rs.getString("type")),
                            Severity.valueOf(rs.getString("severity")),
                            threshold != null ? threshold.doubleValue() : Double.NaN,
                            rs.getBoolean("enabled"),
                            rs.getInt("cooldown_seconds")));
                }, tenantId);
        return map;
    }

    private Map<String, Map<Long, Double>> loadGroupOverrides(Long tenantId) {
        Map<String, Map<Long, Double>> map = new HashMap<>();
        jdbc.query("SELECT r.code AS code, ra.scope_id AS group_id, ra.threshold_override AS thr "
                        + "FROM rule_assignment ra JOIN rule r ON r.id = ra.rule_id "
                        + "WHERE ra.tenant_id = ? AND ra.scope_type = 'GROUP' "
                        + "AND ra.threshold_override IS NOT NULL AND ra.enabled = true",
                rs -> {
                    map.computeIfAbsent(rs.getString("code"), k -> new HashMap<>())
                            .put(rs.getLong("group_id"), rs.getDouble("thr"));
                },
                tenantId);
        return map;
    }

    private Optional<Long> loadVehicleGroup(Long vehicleId) {
        return jdbc.query("SELECT group_id FROM vehicle WHERE id = ?",
                (ResultSetExtractor<Optional<Long>>) rs ->
                        rs.next() ? Optional.ofNullable((Long) rs.getObject("group_id")) : Optional.empty(),
                vehicleId);
    }
}
