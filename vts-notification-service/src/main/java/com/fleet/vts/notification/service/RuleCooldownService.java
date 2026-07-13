package com.fleet.vts.notification.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/** Provides each rule's cooldown_seconds (Caffeine, TTL 60s). Defaults to 300. */
@Service
public class RuleCooldownService {

    private static final int DEFAULT_COOLDOWN = 300;

    private final JdbcTemplate jdbc;
    private final Cache<Long, Map<String, Integer>> byTenant = Caffeine.newBuilder()
            .maximumSize(10_000).expireAfterWrite(Duration.ofSeconds(60)).build();

    public RuleCooldownService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int cooldownSeconds(Long tenantId, String ruleCode) {
        return byTenant.get(tenantId, this::load).getOrDefault(ruleCode, DEFAULT_COOLDOWN);
    }

    private Map<String, Integer> load(Long tenantId) {
        Map<String, Integer> map = new HashMap<>();
        jdbc.query("SELECT code, cooldown_seconds FROM rule WHERE tenant_id = ?",
                rs -> {
                    map.put(rs.getString("code"), rs.getInt("cooldown_seconds"));
                },
                tenantId);
        return map;
    }
}
