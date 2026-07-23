package com.fleet.vts.scheduler.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleet.vts.common.enums.RuleType;
import com.fleet.vts.common.enums.Severity;
import com.fleet.vts.common.event.ViolationEvent;
import com.fleet.vts.common.topic.Topics;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The periodic jobs. Each is ShedLock-guarded (one node only) and recorded via
 * {@link JobRunner}.
 */
@Component
public class ScheduledJobs {

    private static final Logger log = LoggerFactory.getLogger(ScheduledJobs.class);

    private final JobRunner runner;
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public ScheduledJobs(JobRunner runner, JdbcTemplate jdbc,
                         KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.runner = runner;
        this.jdbc = jdbc;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
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

    /**
     * Turn overdue maintenance into a violation on the fleet's live stream. Every 10 minutes.
     *
     * <p>The daily reminder above only counts; this one acts. A vehicle whose odometer has
     * passed its service interval is flagged the same way a speeding one is — a
     * {@code MAINTENANCE_OVERDUE} event on {@code vehicle.violation}. From there the existing
     * pipeline does the rest: processing persists it (resolving {@code rule_id} from the code,
     * which is why the {@code ruleId} on the event is left null), the gateway pushes it to the
     * live map, and notification sends it.
     *
     * <p>The 24-hour {@code NOT EXISTS} guard is the debounce: an overdue vehicle stays overdue
     * for thousands of km, and without it every scan would raise the same violation. One per
     * vehicle per day is what an operator can act on.
     */
    @Scheduled(fixedRate = 600_000)
    @SchedulerLock(name = "maintenance-overdue", lockAtMostFor = "PT9M")
    public void flagOverdueMaintenance() {
        runner.run("maintenance-overdue", () -> {
            List<ViolationEvent> due = jdbc.query("""
                    SELECT v.id AS vehicle_id, v.tenant_id, v.odometer_km, mp.next_due_km,
                           ST_Y(vlp.location::geometry) AS lat, ST_X(vlp.location::geometry) AS lon
                    FROM maintenance_plan mp
                    JOIN vehicle v ON v.id = mp.vehicle_id
                    LEFT JOIN vehicle_last_position vlp ON vlp.vehicle_id = v.id
                    WHERE mp.enabled AND mp.interval_km IS NOT NULL
                      AND v.odometer_km >= mp.next_due_km
                      AND NOT EXISTS (
                          SELECT 1 FROM violation vio
                          WHERE vio.vehicle_id = v.id AND vio.rule_code = 'MAINTENANCE_OVERDUE'
                            AND vio.occurred_at > now() - INTERVAL '24 hours')
                    """,
                    (rs, n) -> ViolationEvent.builder()
                            .tenantId(rs.getLong("tenant_id"))
                            .vehicleId(rs.getLong("vehicle_id"))
                            .ruleCode(RuleType.MAINTENANCE_OVERDUE.name())
                            .ruleType(RuleType.MAINTENANCE_OVERDUE)
                            .severity(Severity.MEDIUM)
                            .occurredAt(Instant.now())
                            .value((double) rs.getLong("odometer_km"))
                            .threshold(rs.getObject("next_due_km") == null
                                    ? null : (double) rs.getLong("next_due_km"))
                            .lat(rs.getObject("lat", Double.class))
                            .lon(rs.getObject("lon", Double.class))
                            .correlationId(UUID.randomUUID().toString())
                            .build());

            for (ViolationEvent e : due) {
                kafkaTemplate.send(Topics.VIOLATION, String.valueOf(e.vehicleId()), toJson(e));
            }
            if (!due.isEmpty()) {
                log.info("Flagged {} vehicle(s) overdue for maintenance", due.size());
            }
            return due.size();
        });
    }

    /**
     * Serialise a violation to JSON for the String producer. The event's shape must match what
     * the {@code vehicle.violation} consumers deserialise, which the shared JSR-310 mapper
     * guarantees; a hand-built JSON string would drift the first time the record changed.
     */
    private String toJson(ViolationEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialise violation event", e);
        }
    }
}
