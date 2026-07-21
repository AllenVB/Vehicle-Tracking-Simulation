package com.fleet.vts.processing.persistence;

import com.fleet.vts.common.enums.TripStatus;
import com.fleet.vts.common.event.GeofenceEvent;
import com.fleet.vts.common.event.TripEvent;
import com.fleet.vts.common.event.ViolationEvent;
import com.fleet.vts.common.topic.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists stream-analytics outputs to the database: geofence events, completed trips
 * (with their breadcrumb) and the violations produced by the stateful rules.
 *
 * <p>Two things the stream topology cannot do for itself, because it never reads the
 * rule/driver tables:
 * <ul>
 *   <li><b>Violations arrive without a {@code rule_id}</b> (which the column requires) and
 *       without a driver. That absence is also how we tell them apart: a violation that
 *       already carries a {@code ruleId} came from the stateless engine and was written by
 *       {@link ViolationWriter} — persisting it again here would duplicate it.</li>
 *   <li><b>A closed trip has no breadcrumb.</b> The route is rebuilt from the telemetry
 *       hypertable at close time (downsampled to ~1 point per 30s); without this,
 *       {@code trip_point} stays empty and the history-route view has nothing to draw.</li>
 * </ul>
 */
@Component
public class StreamOutputPersister {

    private static final Logger log = LoggerFactory.getLogger(StreamOutputPersister.class);

    private final JdbcTemplate jdbc;
    private final Map<String, Long> ruleIds = new ConcurrentHashMap<>();

    public StreamOutputPersister(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @KafkaListener(topics = Topics.GEOFENCE_EVENT, containerFactory = "geofenceListenerFactory")
    public void onGeofence(GeofenceEvent e) {
        jdbc.update("""
                INSERT INTO geofence_event
                    (tenant_id, geofence_id, vehicle_id, driver_id, event_type, occurred_at, location)
                VALUES (?, ?, ?, ?, ?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography)
                """,
                e.tenantId(), e.geofenceId(), e.vehicleId(), e.driverId(),
                e.eventType() == null ? null : e.eventType().name(),
                ts(e.occurredAt()), e.lon(), e.lat());
    }

    /** Violations from the stateful rules (harsh braking, sustained speeding, idling). */
    @KafkaListener(topics = Topics.VIOLATION, containerFactory = "streamViolationListenerFactory",
            groupId = "vts-processing-stream-violations")
    public void onViolation(ViolationEvent e) {
        if (e == null || e.ruleId() != null || e.vehicleId() == null || e.ruleCode() == null) {
            return;   // already persisted by the stateless path (or unusable)
        }
        Long ruleId = ruleId(e.tenantId(), e.ruleCode());
        if (ruleId == null) {
            log.warn("No rule row for code {} (tenant {}), skipping violation", e.ruleCode(), e.tenantId());
            return;
        }
        jdbc.update("""
                INSERT INTO violation
                    (tenant_id, vehicle_id, driver_id, device_id, rule_id, rule_code,
                     type, severity, occurred_at, value, threshold, location)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography)
                """,
                e.tenantId(), e.vehicleId(), driverAt(e.vehicleId(), e.occurredAt()), e.deviceId(),
                ruleId, e.ruleCode(),
                e.ruleType() == null ? null : e.ruleType().name(),
                e.severity() == null ? null : e.severity().name(),
                ts(e.occurredAt()), e.value(), e.threshold(), e.lon(), e.lat());
    }

    @KafkaListener(topics = Topics.TRIP, containerFactory = "tripListenerFactory")
    @Transactional
    public void onTrip(TripEvent e) {
        if (e.status() != TripStatus.CLOSED || e.vehicleId() == null) {
            return; // only completed trips are persisted
        }
        Long driverId = e.driverId() != null ? e.driverId() : driverAt(e.vehicleId(), e.startedAt());
        Violations counts = violationsDuring(e);
        double distanceKm = e.distanceKm() == null ? 0 : e.distanceKm();
        int score = TripScore.of(distanceKm, counts.speeding(), counts.harsh(), counts.idling());

        Long tripId = jdbc.queryForObject("""
                INSERT INTO trip
                    (tenant_id, vehicle_id, driver_id, started_at, ended_at,
                     start_location, end_location, distance_km, avg_speed_kmh, max_speed_kmh,
                     violation_count, score, status)
                VALUES (?, ?, ?, ?, ?,
                        ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                        ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                        ?, ?, ?, ?, ?, 'CLOSED')
                RETURNING id
                """, Long.class,
                e.tenantId(), e.vehicleId(), driverId, ts(e.startedAt()), ts(e.endedAt()),
                e.startLon(), e.startLat(), e.endLon(), e.endLat(),
                e.distanceKm(), e.avgSpeedKmh(), e.maxSpeedKmh(),
                counts.total(), score);

        int points = persistBreadcrumb(tripId, e);
        if (points == 0) {
            log.warn("Trip {} (vehicle {}) closed with no telemetry breadcrumb", tripId, e.vehicleId());
        }
    }

    /** Violation tally for one journey, by the kinds the score weighs. */
    private record Violations(int total, int speeding, int harsh, int idling) {

        static final Violations NONE = new Violations(0, 0, 0, 0);
    }

    /**
     * What went wrong during this journey, counted from the violation table.
     *
     * <p>The trip event cannot tell us: the stream topology emits {@code violationCount} as a
     * hard-coded zero, because the rules that detect violations run in a different part of the
     * graph from the one that tracks trips and never meet. Counting here — where the trip's
     * window and the persisted violations are both in reach — is what makes both the stored
     * {@code violation_count} and the score mean anything.
     *
     * <p>Safe to read now: violations are written as their readings are processed, so those
     * inside the window are already committed by the time a trip closes 90 s after its last
     * movement.
     */
    private Violations violationsDuring(TripEvent e) {
        if (e.startedAt() == null || e.endedAt() == null) {
            return Violations.NONE;
        }
        return jdbc.query("""
                SELECT count(*)                                            AS total,
                       count(*) FILTER (WHERE rule_code = 'SPEED_LIMIT')   AS speeding,
                       count(*) FILTER (WHERE rule_code = 'HARSH_BRAKING') AS harsh,
                       count(*) FILTER (WHERE rule_code = 'IDLING')        AS idling
                FROM violation
                WHERE vehicle_id = ? AND occurred_at >= ? AND occurred_at <= ?
                """,
                (ResultSetExtractor<Violations>) rs -> rs.next()
                        ? new Violations(rs.getInt("total"), rs.getInt("speeding"),
                                         rs.getInt("harsh"), rs.getInt("idling"))
                        : Violations.NONE,
                e.vehicleId(), ts(e.startedAt()), ts(e.endedAt()));
    }

    /**
     * Rebuild the trip's route from telemetry. Downsampled to one point per 30 seconds:
     * a long trip at a 1-second tick would otherwise be thousands of rows for a line the
     * map draws at a few hundred points anyway.
     */
    private int persistBreadcrumb(Long tripId, TripEvent e) {
        return jdbc.update("""
                INSERT INTO trip_point (trip_id, seq, ts, location, speed_kmh, heading)
                SELECT ?, row_number() OVER (ORDER BY s.ts), s.ts, s.location, s.speed_kmh, s.heading
                FROM (
                    SELECT DISTINCT ON (time_bucket('30 seconds', ts))
                           ts, location, speed_kmh, heading
                    FROM telemetry
                    WHERE vehicle_id = ? AND ts >= ? AND ts <= ? AND location IS NOT NULL
                    ORDER BY time_bucket('30 seconds', ts), ts
                ) s
                ON CONFLICT (trip_id, seq) DO NOTHING
                """,
                tripId, e.vehicleId(), ts(e.startedAt()), ts(e.endedAt()));
    }

    /** The rule row backing a code; the violation table requires the id, the stream has only the code. */
    private Long ruleId(Long tenantId, String code) {
        return ruleIds.computeIfAbsent(tenantId + ":" + code, k -> {
            try {
                return jdbc.queryForObject(
                        "SELECT id FROM rule WHERE tenant_id = ? AND code = ?", Long.class, tenantId, code);
            } catch (Exception ex) {
                return null;
            }
        });
    }

    /** Driver on the vehicle at that moment, via the temporal assignment (same rule as violations). */
    private Long driverAt(Long vehicleId, Instant when) {
        if (vehicleId == null || when == null) {
            return null;
        }
        try {
            return jdbc.queryForObject("""
                    SELECT driver_id FROM vehicle_driver_assignment
                    WHERE vehicle_id = ? AND assigned_at <= ?
                      AND (released_at IS NULL OR released_at > ?)
                    ORDER BY assigned_at DESC LIMIT 1
                    """, Long.class, vehicleId, ts(when), ts(when));
        } catch (Exception ex) {
            return null;
        }
    }

    private OffsetDateTime ts(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
