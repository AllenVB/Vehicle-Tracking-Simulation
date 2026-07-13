package com.fleet.vts.common.event;

import com.fleet.vts.common.enums.GeofenceEventType;
import lombok.Builder;

import java.time.Instant;

/**
 * A geofence enter/exit event on {@code vehicle.geofence.event}, produced by the
 * analytics topology using PostGIS point-in-polygon against the geofence
 * GlobalKTable.
 */
@Builder
public record GeofenceEvent(
        Long tenantId,
        Long vehicleId,
        Long driverId,
        Long geofenceId,
        String geofenceName,
        GeofenceEventType eventType,
        Instant occurredAt,
        Double lat,
        Double lon,
        String correlationId
) {
}
