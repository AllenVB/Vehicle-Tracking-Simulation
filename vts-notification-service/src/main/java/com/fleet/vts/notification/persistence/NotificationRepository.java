package com.fleet.vts.notification.persistence;

import com.fleet.vts.notification.sender.NotificationMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;

/** Persists notifications and their per-channel delivery attempts. */
@Component
public class NotificationRepository {

    private static final String INSERT_NOTIFICATION = """
            INSERT INTO notification
                (tenant_id, user_id, driver_id, vehicle_id, rule_code, severity,
                 channel, title, body, status, source_violation_id, sent_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String INSERT_ATTEMPT = """
            INSERT INTO notification_delivery_attempt
                (notification_id, channel, attempt_no, status, error, attempted_at)
            VALUES (?, ?, 1, ?, ?, now())
            """;

    private final JdbcTemplate jdbc;

    public NotificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Long insertNotification(NotificationMessage m, String status) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        boolean sent = "SENT".equals(status);
        jdbc.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(INSERT_NOTIFICATION, new String[]{"id"});
            ps.setObject(1, m.tenantId());
            ps.setObject(2, m.userId());
            ps.setObject(3, m.driverId());
            ps.setObject(4, m.vehicleId());
            ps.setString(5, m.ruleCode());
            ps.setString(6, m.severity() == null ? null : m.severity().name());
            ps.setString(7, m.channel().name());
            ps.setString(8, m.title());
            ps.setString(9, m.body());
            ps.setString(10, status);
            ps.setObject(11, m.sourceViolationId());
            ps.setTimestamp(12, sent ? Timestamp.from(Instant.now()) : null);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? null : key.longValue();
    }

    public void insertAttempt(Long notificationId, String channel, boolean ok, String error) {
        jdbc.update(INSERT_ATTEMPT, notificationId, channel, ok ? "SUCCESS" : "FAILED", error);
    }
}
