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

/**
 * Resolves the effective rules for a vehicle. Thresholds are never hard-coded:
 * they come from the {@code rule} table with {@code rule_assignment} GROUP
 * overrides applied (e.g. trucks 80, cars 110). Everything is cached in Caffeine
 * (TTL 60s) and can be flushed on a rule-change invalidation event.
 */
@Service
public class RuleConfigService {

    private record RuleDef(Long ruleId, String code, RuleType type, Severity severity,
                           double thresholdDefault, boolean enabled) {
    }

    private final JdbcTemplate jdbc;

    private final Cache<Long, Map<String, RuleDef>> rulesByTenant = build();
    private final Cache<Long, Map<String, Map<Long, Double>>> groupOverridesByTenant = build();
    private final Cache<Long, Optional<Long>> vehicleGroup = build();

    public RuleConfigService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static <K, V> Cache<K, V> build() {
        return Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofSeconds(60))
                .build();
    }

    /** Effective rules for a vehicle, keyed by rule code. */
    public Map<String, RuleView> rulesFor(Long tenantId, Long vehicleId) {
        Map<String, RuleDef> defs = rulesByTenant.get(tenantId, this::loadRules);
        Map<String, Map<Long, Double>> overrides =
                groupOverridesByTenant.get(tenantId, this::loadGroupOverrides);
        Long groupId = vehicleGroup.get(vehicleId, this::loadVehicleGroup).orElse(null);

        Map<String, RuleView> result = new LinkedHashMap<>();
        for (RuleDef def : defs.values()) {
            double threshold = def.thresholdDefault();
            if (groupId != null) {
                Double override = overrides.getOrDefault(def.code(), Map.of()).get(groupId);
                if (override != null) {
                    threshold = override;
                }
            }
            result.put(def.code(), new RuleView(def.ruleId(), def.code(), def.type(),
                    def.severity(), threshold, def.enabled()));
        }
        return result;
    }

    /** Flush all caches (invoked when a rule changes). */
    public void invalidateAll() {
        rulesByTenant.invalidateAll();
        groupOverridesByTenant.invalidateAll();
        vehicleGroup.invalidateAll();
    }

    private Map<String, RuleDef> loadRules(Long tenantId) {
        Map<String, RuleDef> map = new HashMap<>();
        jdbc.query("SELECT id, code, type, severity, threshold_value, enabled "
                        + "FROM rule WHERE tenant_id = ?",
                rs -> {
                    Number threshold = (Number) rs.getObject("threshold_value");
                    map.put(rs.getString("code"), new RuleDef(
                            rs.getLong("id"),
                            rs.getString("code"),
                            RuleType.valueOf(rs.getString("type")),
                            Severity.valueOf(rs.getString("severity")),
                            threshold != null ? threshold.doubleValue() : Double.NaN,
                            rs.getBoolean("enabled")));
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
