package com.fleet.vts.gateway.web.dto;

import java.time.Instant;

/** One position on a vehicle's day trail (breadcrumb), read from the telemetry hypertable. */
public record TrackPointDto(Instant ts, double lat, double lon, Integer speedKmh) {
}
