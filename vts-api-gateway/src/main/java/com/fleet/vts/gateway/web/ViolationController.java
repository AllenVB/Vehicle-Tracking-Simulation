package com.fleet.vts.gateway.web;

import com.fleet.vts.gateway.security.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Violation queries with keyset (cursor) pagination over the hypertable, ordered
 * by (occurred_at, id) descending. Filters: date range, vehicle, rule, severity.
 */
@RestController
@RequestMapping("/api/v1/violations")
public class ViolationController {

    public record ViolationView(Long id, Long vehicleId, Long driverId, String ruleCode, String type,
                                String severity, Instant occurredAt, Double value, Double threshold,
                                Double lat, Double lon) {
    }

    public record Page(List<ViolationView> items, String nextCursor) {
    }

    private final JdbcTemplate jdbc;

    public ViolationController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public Page list(@AuthenticationPrincipal Jwt jwt,
                     @RequestParam(required = false) Instant from,
                     @RequestParam(required = false) Instant to,
                     @RequestParam(required = false) Long vehicleId,
                     @RequestParam(required = false) String ruleCode,
                     @RequestParam(required = false) String severity,
                     @RequestParam(required = false) String cursor,
                     @RequestParam(defaultValue = "50") int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, vehicle_id, driver_id, rule_code, type, severity, occurred_at, value, threshold, "
                        + "ST_Y(location::geometry) AS lat, ST_X(location::geometry) AS lon "
                        + "FROM violation WHERE tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(CurrentUser.tenantId(jwt));
        if (from != null) {
            sql.append(" AND occurred_at >= ?");
            args.add(OffsetDateTime.ofInstant(from, ZoneOffset.UTC));
        }
        if (to != null) {
            sql.append(" AND occurred_at < ?");
            args.add(OffsetDateTime.ofInstant(to, ZoneOffset.UTC));
        }
        if (vehicleId != null) {
            sql.append(" AND vehicle_id = ?");
            args.add(vehicleId);
        }
        if (ruleCode != null) {
            sql.append(" AND rule_code = ?");
            args.add(ruleCode);
        }
        if (severity != null) {
            sql.append(" AND severity = ?");
            args.add(severity);
        }
        if (cursor != null) {
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor)).split(":");
            sql.append(" AND (occurred_at, id) < (?, ?)");
            args.add(OffsetDateTime.ofInstant(Instant.ofEpochMilli(Long.parseLong(parts[0])), ZoneOffset.UTC));
            args.add(Long.parseLong(parts[1]));
        }
        int capped = Math.min(Math.max(limit, 1), 200);
        sql.append(" ORDER BY occurred_at DESC, id DESC LIMIT ?");
        args.add(capped + 1);

        List<ViolationView> rows = jdbc.query(sql.toString(), (rs, n) -> new ViolationView(
                rs.getLong("id"), rs.getLong("vehicle_id"), (Long) rs.getObject("driver_id"),
                rs.getString("rule_code"), rs.getString("type"), rs.getString("severity"),
                rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                toDouble(rs.getObject("value")), toDouble(rs.getObject("threshold")),
                toDouble(rs.getObject("lat")), toDouble(rs.getObject("lon"))), args.toArray());

        String nextCursor = null;
        if (rows.size() > capped) {
            ViolationView last = rows.get(capped - 1);
            rows = rows.subList(0, capped);
            nextCursor = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    (last.occurredAt().toEpochMilli() + ":" + last.id()).getBytes());
        }
        return new Page(rows, nextCursor);
    }

    /** NUMERIC columns arrive as BigDecimal; normalise to Double (null-safe). */
    private static Double toDouble(Object value) {
        return value == null ? null : ((Number) value).doubleValue();
    }

    @PostMapping("/{id}/ack")
    public ResponseEntity<Map<String, Object>> ack(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        long tenant = CurrentUser.tenantId(jwt);
        List<OffsetDateTime> occurred = jdbc.queryForList(
                "SELECT occurred_at FROM violation WHERE id = ? AND tenant_id = ?",
                OffsetDateTime.class, id, tenant);
        if (occurred.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        jdbc.update("INSERT INTO violation_ack (tenant_id, violation_id, violation_occurred_at, acked_by) "
                        + "VALUES (?, ?, ?, ?) ON CONFLICT (violation_id) DO NOTHING",
                tenant, id, occurred.get(0), CurrentUser.userId(jwt));
        return ResponseEntity.ok(Map.of("violationId", id, "acked", true));
    }
}
