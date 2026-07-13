package com.fleet.vts.gateway.live;

import java.time.Instant;

/** Latest known position of a vehicle for the live map. */
public record Position(Long vehicleId, Double lat, Double lon, Integer speedKmh,
                       Integer heading, Instant ts) {
}
