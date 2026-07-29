package com.fleet.vts.gateway.live;

import java.time.Instant;

/**
 * Latest known position of a vehicle for the live map.
 *
 * <p>{@code fuelPct} rides along because the map marks a low tank visually, and asking the
 * reporting API per vehicle for that would mean a query per marker per frame. It is nullable:
 * a device that reports no tank level leaves it unset rather than claiming zero.
 *
 * <p>{@code accuracy} is the GPS horizontal accuracy in metres reported by the device (a real
 * phone/tracker supplies it; a synthetic source leaves it null). The map draws it as a
 * confidence circle around the marker.
 */
public record Position(Long vehicleId, Double lat, Double lon, Integer speedKmh,
                       Integer heading, Integer fuelPct, Double accuracy, Instant ts) {
}
