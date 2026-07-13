package com.fleet.vts.processing.persistence;

import com.fleet.vts.common.enums.TripStatus;
import com.fleet.vts.common.event.GeofenceEvent;
import com.fleet.vts.common.event.TripEvent;
import com.fleet.vts.common.topic.Topics;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Persists stream-analytics outputs to the database: geofence enter/exit events
 * and completed (CLOSED) trips. ONGOING trips are transient (live view only).
 */
@Component
public class StreamOutputPersister {

    private final JdbcTemplate jdbc;

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

    @KafkaListener(topics = Topics.TRIP, containerFactory = "tripListenerFactory")
    public void onTrip(TripEvent e) {
        if (e.status() != TripStatus.CLOSED) {
            return; // only completed trips are persisted
        }
        jdbc.update("""
                INSERT INTO trip
                    (tenant_id, vehicle_id, driver_id, started_at, ended_at,
                     start_location, end_location, distance_km, avg_speed_kmh, max_speed_kmh,
                     violation_count, status)
                VALUES (?, ?, ?, ?, ?,
                        ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                        ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                        ?, ?, ?, ?, 'CLOSED')
                """,
                e.tenantId(), e.vehicleId(), e.driverId(), ts(e.startedAt()), ts(e.endedAt()),
                e.startLon(), e.startLat(), e.endLon(), e.endLat(),
                e.distanceKm(), e.avgSpeedKmh(), e.maxSpeedKmh(),
                e.violationCount() == null ? 0 : e.violationCount());
    }

    private OffsetDateTime ts(java.time.Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
