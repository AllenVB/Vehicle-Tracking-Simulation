package com.fleet.vts.common.event;

import com.fleet.vts.common.enums.TripStatus;
import lombok.Builder;

import java.time.Instant;

/**
 * A trip lifecycle event on {@code vehicle.trip}. Emitted as ONGOING when a trip
 * opens and CLOSED when it ends (5 min of no movement), carrying the computed
 * distance, speed and violation aggregates.
 */
@Builder
public record TripEvent(
        Long tenantId,
        Long vehicleId,
        Long driverId,
        Long tripId,
        TripStatus status,
        Instant startedAt,
        Instant endedAt,
        Double startLat,
        Double startLon,
        Double endLat,
        Double endLon,
        Double distanceKm,
        Double avgSpeedKmh,
        Integer maxSpeedKmh,
        Integer violationCount,
        String correlationId
) {
}
