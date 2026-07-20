package com.fleet.vts.gateway.live;

import java.time.Instant;

/**
 * Latest known position of a vehicle for the live map.
 *
 * <p>{@code fuelPct} rides along because the map marks a low tank visually, and asking the
 * reporting API per vehicle for that would mean a query per marker per frame. It is nullable:
 * a device that reports no tank level leaves it unset rather than claiming zero.
 */
public record Position(Long vehicleId, Double lat, Double lon, Integer speedKmh,
                       Integer heading, Integer fuelPct, Instant ts) {
}
