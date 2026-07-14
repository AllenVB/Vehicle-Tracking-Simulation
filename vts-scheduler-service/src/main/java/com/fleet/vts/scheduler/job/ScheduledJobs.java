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

    /**
     * Driver score for a day, from what the driver actually did: distance driven (trips)
     * and violations of every kind. Penalties are per 100 km — otherwise the driver who
     * simply drives more looks like the worst driver, which is the classic way to get a
     * scoreboard nobody trusts. A driver with almost no distance is not scored harshly.
     */
    private static final String SCORE_FOR_DAY = """
            WITH dist AS (
                SELECT tenant_id, driver_id, sum(distance_km) AS km
                FROM trip
                WHERE driver_id IS NOT NULL
                  AND started_at >= ?::date AND started_at < (?::date + 1)
                GROUP BY 1, 2
            ),
            viol AS (
                SELECT tenant_id, driver_id,
                       count(*)                                              AS total,
                       count(*) FILTER (WHERE rule_code = 'SPEED_LIMIT')     AS speeding,
                       count(*) FILTER (WHERE rule_code = 'HARSH_BRAKING')   AS harsh,
                       count(*) FILTER (WHERE rule_code = 'IDLING')          AS idling
                FROM violation
                WHERE driver_id IS NOT NULL
                  AND occurred_at >= ?::date AND occurred_at < (?::date + 1)
                GROUP BY 1, 2
            ),
            merged AS (
                SELECT coalesce(v.tenant_id, d.tenant_id) AS tenant_id,
                       coalesce(v.driver_id, d.driver_id) AS driver_id,
                       coalesce(d.km, 0)                  AS km,
                       coalesce(v.total, 0)               AS total,
                       coalesce(v.speeding, 0)            AS speeding,
                       coalesce(v.harsh, 0)               AS harsh,
                       coalesce(v.idling, 0)              AS idling
                FROM viol v FULL OUTER JOIN dist d
                  ON v.tenant_id = d.tenant_id AND v.driver_id = d.driver_id
            )
            INSERT INTO driver_score_daily
                (tenant_id, driver_id, score_date, distance_km, harsh_braking_count,
                 speeding_count, idling_seconds, violation_count, score)
            SELECT tenant_id, driver_id, ?::date, km, harsh, speeding, idling * 60, total,
                   greatest(0, least(100,
                       100 - ((speeding * 3 + harsh * 5 + idling * 2)
                              / greatest(km, 25)) * 100))
            FROM merged
            ON CONFLICT (tenant_id, driver_id, score_date) DO UPDATE SET
                distance_km         = EXCLUDED.distance_km,
                harsh_braking_count = EXCLUDED.harsh_braking_count,
                speeding_count      = EXCLUDED.speeding_count,
                idling_seconds      = EXCLUDED.idling_seconds,
                violation_count     = EXCLUDED.violation_count,
                score               = EXCLUDED.score
            """;

    /** Yesterday's final score, once the day is closed. 00:30 every day. */
    @Scheduled(cron = "0 30 0 * * *")
    @SchedulerLock(name = "driver-scoring")
    public void computeDriverScores() {
        runner.run("driver-scoring", () -> scoreDay("(now() - INTERVAL '1 day')::date"));
    }

    /**
     * Today's score, refreshed continuously. Without this the scoreboard would be frozen
     * until 00:30 — i.e. it would look broken for the entire time anyone is watching.
     */
    @Scheduled(fixedDelay = 120_000)
    @SchedulerLock(name = "driver-scoring-today", lockAtMostFor = "PT5M")
    public void computeTodayDriverScores() {
        runner.run("driver-scoring-today", () -> scoreDay("now()::date"));
    }

    private int scoreDay(String dayExpr) {
        String sql = SCORE_FOR_DAY.replace("?::date", dayExpr);
        return jdbc.update(sql);
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
