package com.fleet.vts.scheduler.job;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The four periodic jobs. Each is ShedLock-guarded (one node only) and recorded
 * via {@link JobRunner}.
 */
@Component
public class ScheduledJobs {

    private final JobRunner runner;
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public ScheduledJobs(JobRunner runner, JdbcTemplate jdbc, KafkaTemplate<String, String> kafkaTemplate) {
        this.runner = runner;
        this.jdbc = jdbc;
        this.kafkaTemplate = kafkaTemplate;
    }

    /** Outbox publisher fallback (used when Debezium is not deployed). Every 5s. */
    @Scheduled(fixedDelay = 5000)
    @SchedulerLock(name = "outbox-publisher", lockAtMostFor = "PT1M")
    public void publishOutbox() {
        runner.run("outbox-publisher", () -> {
            List<Object[]> pending = jdbc.query(
                    "SELECT id, topic, partition_key, payload::text FROM outbox_event "
                            + "WHERE status = 'PENDING' ORDER BY created_at LIMIT 500",
                    (rs, n) -> new Object[]{rs.getLong("id"), rs.getString("topic"),
                            rs.getString("partition_key"), rs.getString("payload")});
            for (Object[] row : pending) {
                kafkaTemplate.send((String) row[1], (String) row[2], (String) row[3]);
                jdbc.update("UPDATE outbox_event SET status = 'PUBLISHED', published_at = now() WHERE id = ?", row[0]);
            }
            return pending.size();
        });
    }

    /** Mark devices offline after 5 minutes without a heartbeat. Every minute. */
    @Scheduled(fixedRate = 60_000)
    @SchedulerLock(name = "device-offline")
    public void detectOfflineDevices() {
        runner.run("device-offline", () -> jdbc.update(
                "UPDATE device SET status = 'OFFLINE', updated_at = now() "
                        + "WHERE status = 'ACTIVE' AND (last_seen_at IS NULL OR last_seen_at < now() - INTERVAL '5 minutes')"));
    }

    /** Count maintenance plans due by odometer or date. 08:00 every day. */
    @Scheduled(cron = "0 0 8 * * *")
    @SchedulerLock(name = "maintenance-reminder")
    public void maintenanceReminders() {
        runner.run("maintenance-reminder", () -> {
            Integer due = jdbc.queryForObject("""
                    SELECT count(*) FROM maintenance_plan mp JOIN vehicle v ON v.id = mp.vehicle_id
                    WHERE mp.enabled
                      AND ((mp.next_due_km IS NOT NULL AND v.odometer_km >= mp.next_due_km)
                        OR (mp.next_due_at IS NOT NULL AND mp.next_due_at <= now()))
                    """, Integer.class);
            return due == null ? 0 : due;
        });
    }
}
