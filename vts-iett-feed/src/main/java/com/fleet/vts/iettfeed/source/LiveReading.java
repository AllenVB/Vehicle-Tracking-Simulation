package com.fleet.vts.iettfeed.source;

import java.time.Instant;

/**
 * A source-agnostic position reading: a stable vehicle id, a valid WGS84
 * position, and the reading time ({@code null} if the source did not provide a
 * parseable timestamp). Speed and heading are intentionally absent — they are
 * derived downstream from successive readings.
 */
public record LiveReading(String vehicleId, double lat, double lon, Instant ts) {
}
