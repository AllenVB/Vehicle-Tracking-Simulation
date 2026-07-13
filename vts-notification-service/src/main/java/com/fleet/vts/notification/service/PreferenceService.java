package com.fleet.vts.notification.service;

import com.fleet.vts.common.enums.NotificationChannel;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Time;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads enabled notification preferences per tenant (Caffeine, TTL 60s) and
 * filters them for a rule (a NULL rule_code preference applies to all rules).
 */
@Service
public class PreferenceService {

    private record Row(Long userId, NotificationChannel channel, String ruleCode,
                       java.time.LocalTime quietStart, java.time.LocalTime quietEnd) {
    }

    private final JdbcTemplate jdbc;
    private final Cache<Long, List<Row>> byTenant = Caffeine.newBuilder()
            .maximumSize(10_000).expireAfterWrite(Duration.ofSeconds(60)).build();

    public PreferenceService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Preference> preferencesFor(Long tenantId, String ruleCode) {
        List<Row> rows = byTenant.get(tenantId, this::load);
        List<Preference> result = new ArrayList<>();
        for (Row r : rows) {
            if (r.ruleCode() == null || r.ruleCode().equals(ruleCode)) {
                result.add(new Preference(r.userId(), r.channel(), r.quietStart(), r.quietEnd()));
            }
        }
        return result;
    }

    private List<Row> load(Long tenantId) {
        return jdbc.query(
                "SELECT user_id, channel, rule_code, quiet_hours_start, quiet_hours_end "
                        + "FROM notification_preference WHERE tenant_id = ? AND enabled = true",
                (rs, n) -> new Row(
                        rs.getLong("user_id"),
                        NotificationChannel.valueOf(rs.getString("channel")),
                        rs.getString("rule_code"),
                        toLocalTime(rs.getTime("quiet_hours_start")),
                        toLocalTime(rs.getTime("quiet_hours_end"))),
                tenantId);
    }

    private static java.time.LocalTime toLocalTime(Time time) {
        return time == null ? null : time.toLocalTime();
    }
}
