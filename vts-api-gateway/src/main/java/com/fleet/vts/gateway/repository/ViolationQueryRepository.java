package com.fleet.vts.gateway.repository;

import com.fleet.vts.gateway.web.dto.ViolationDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Violation reads and acknowledgement, over the hypertable.
 *
 * <p>Separate from the JPA {@link ViolationRepository} on purpose: these queries are
 * keyset-paginated and unwrap PostGIS geography into lat/lon columns, neither of which JPA
 * expresses. So this is JdbcTemplate against the read model, and it maps straight to a DTO
 * rather than to an entity — there is no entity for a row shaped like this.
 */
@Repository
public class ViolationQueryRepository {

    /**
     * A page request. {@code cursorOccurredAt}/{@code cursorId} are the keyset position,
     * both null for the first page; the rest are optional filters.
     */
    public record PageQuery(
            long tenantId,
            Instant from,
            Instant to,
            Long vehicleId,
            String ruleCode,
            String severity,
            Instant cursorOccurredAt,
            Long cursorId,
            int limit) {
    }

    private static final RowMapper<ViolationDto> ROW_MAPPER = (rs, n) -> new ViolationDto(
            rs.getLong("id"),
            rs.getLong("vehicle_id"),
            (Long) rs.getObject("driver_id"),
            rs.getString("rule_code"),
            rs.getString("type"),
            rs.getString("severity"),
            rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
            toDouble(rs.getObject("value")),
            toDouble(rs.getObject("threshold")),
            toDouble(rs.getObject("lat")),
            toDouble(rs.getObject("lon")));

    private final JdbcTemplate jdbc;

    public ViolationQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Violations newest-first, ordered by {@code (occurred_at, id)} descending — the same
     * tuple the cursor addresses, so the keyset comparison is a single index range scan.
     *
     * <p>Returns up to {@code limit} rows. The caller asks for one more than it intends to
     * show to learn whether a further page exists.
     */
    public List<ViolationDto> findPage(PageQuery query) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, vehicle_id, driver_id, rule_code, type, severity, occurred_at, value, threshold, "
                        + "ST_Y(location::geometry) AS lat, ST_X(location::geometry) AS lon "
                        + "FROM violation WHERE tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(query.tenantId());

        if (query.from() != null) {
            sql.append(" AND occurred_at >= ?");
            args.add(atUtc(query.from()));
        }
        if (query.to() != null) {
            sql.append(" AND occurred_at < ?");
            args.add(atUtc(query.to()));
        }
        if (query.vehicleId() != null) {
            sql.append(" AND vehicle_id = ?");
            args.add(query.vehicleId());
        }
        if (query.ruleCode() != null) {
            sql.append(" AND rule_code = ?");
            args.add(query.ruleCode());
        }
        if (query.severity() != null) {
            sql.append(" AND severity = ?");
            args.add(query.severity());
        }
        if (query.cursorOccurredAt() != null && query.cursorId() != null) {
            sql.append(" AND (occurred_at, id) < (?, ?)");
            args.add(atUtc(query.cursorOccurredAt()));
            args.add(query.cursorId());
        }

        sql.append(" ORDER BY occurred_at DESC, id DESC LIMIT ?");
        args.add(query.limit());

        return jdbc.query(sql.toString(), ROW_MAPPER, args.toArray());
    }

    /**
     * When the violation occurred, or empty if it does not exist for this tenant. The ack
     * insert needs the timestamp because {@code violation_ack} carries it as part of the
     * hypertable's key.
     */
    public Optional<OffsetDateTime> findOccurredAt(long id, long tenantId) {
        return jdbc.queryForList(
                        "SELECT occurred_at FROM violation WHERE id = ? AND tenant_id = ?",
                        OffsetDateTime.class, id, tenantId)
                .stream()
                .findFirst();
    }

    /** Idempotent: acknowledging an already-acknowledged violation is a no-op. */
    public void insertAck(long tenantId, long violationId, OffsetDateTime occurredAt, long ackedBy) {
        jdbc.update("INSERT INTO violation_ack (tenant_id, violation_id, violation_occurred_at, acked_by) "
                        + "VALUES (?, ?, ?, ?) ON CONFLICT (violation_id) DO NOTHING",
                tenantId, violationId, occurredAt, ackedBy);
    }

    private static OffsetDateTime atUtc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** NUMERIC columns arrive as BigDecimal; normalise to Double (null-safe). */
    private static Double toDouble(Object value) {
        return value == null ? null : ((Number) value).doubleValue();
    }
}
