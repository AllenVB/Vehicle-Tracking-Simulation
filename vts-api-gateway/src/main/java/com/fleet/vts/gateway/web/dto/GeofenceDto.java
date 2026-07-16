package com.fleet.vts.gateway.web.dto;

/**
 * An active geofence, its area serialised as GeoJSON so the map can draw the zone the
 * enter/exit alerts refer to. {@code kind} is EXCLUSION or INCLUSION.
 */
public record GeofenceDto(
        Long id,
        String name,
        String kind,
        String geojson) {
}
