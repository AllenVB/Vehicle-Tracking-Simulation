package com.fleet.vts.processing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleet.vts.common.event.ViolationEvent;
import com.fleet.vts.common.topic.Topics;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Persists violations and their outbox rows in the SAME transaction, so a
 * violation is never written without its outbox event (and vice versa). The
 * outbox publisher (scheduler / Debezium) later relays the rows to Kafka.
 */
@Component
public class ViolationWriter {

    private static final String INSERT_VIOLATION = """
            INSERT INTO violation
                (tenant_id, vehicle_id, driver_id, device_id, rule_id, rule_code,
                 type, severity, occurred_at, value, threshold, location)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography)
            """;

    private static final String INSERT_OUTBOX = """
            INSERT INTO outbox_event
                (tenant_id, aggregate_type, aggregate_id, event_type, topic, partition_key, payload, status)
            VALUES (?, 'violation', ?, 'VIOLATION_CREATED', ?, ?, ?::jsonb, 'PENDING')
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ViolationWriter(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void persistBatch(List<ViolationEvent> violations) {
        if (violations.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(INSERT_VIOLATION, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                ViolationEvent v = violations.get(i);
                ps.setObject(1, v.tenantId());
                ps.setObject(2, v.vehicleId());
                ps.setObject(3, v.driverId());
                ps.setObject(4, v.deviceId());
                ps.setObject(5, v.ruleId());
                ps.setString(6, v.ruleCode());
                ps.setString(7, v.ruleType() == null ? null : v.ruleType().name());
                ps.setString(8, v.severity() == null ? null : v.severity().name());
                ps.setObject(9, v.occurredAt() == null ? null : v.occurredAt().atOffset(ZoneOffset.UTC));
                ps.setObject(10, v.value());
                ps.setObject(11, v.threshold());
                ps.setObject(12, v.lon());
                ps.setObject(13, v.lat());
            }

            @Override
            public int getBatchSize() {
                return violations.size();
            }
        });

        jdbc.batchUpdate(INSERT_OUTBOX, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                ViolationEvent v = violations.get(i);
                ps.setObject(1, v.tenantId());
                ps.setString(2, String.valueOf(v.vehicleId()));
                ps.setString(3, Topics.VIOLATION);
                ps.setString(4, String.valueOf(v.vehicleId()));
                ps.setString(5, toJson(v));
            }

            @Override
            public int getBatchSize() {
                return violations.size();
            }
        });
    }

    private String toJson(ViolationEvent v) {
        try {
            return objectMapper.writeValueAsString(v);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize violation for outbox", e);
        }
    }
}
