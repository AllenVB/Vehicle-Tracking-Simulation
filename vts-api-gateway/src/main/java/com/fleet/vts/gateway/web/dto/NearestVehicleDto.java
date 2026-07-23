package com.fleet.vts.gateway.web.dto;

/**
 * A vehicle returned by a nearest-vehicle search: its id, how far it is from the query point
 * ({@code distanceKm}, straight-line), and where it currently sits. Coordinates come straight
 * back from the Redis geospatial index, so the caller need not re-look-up positions.
 */
public record NearestVehicleDto(long vehicleId, double distanceKm, Double lat, Double lon) {
}
