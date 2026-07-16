package com.fleet.vts.gateway.web.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One minute of a vehicle's telemetry, read from the {@code telemetry_1min} continuous
 * aggregate rather than the raw hypertable.
 *
 * <p>Every measure is nullable: the aggregate reports whatever the devices sent, and a
 * vehicle that reported no battery or fuel in the bucket leaves those null.
 */
public record TelemetryBucketDto(
        Instant bucket,
        BigDecimal avgSpeedKmh,
        Integer maxSpeedKmh,
        Integer minBattery,
        Integer minFuelPct,
        long sampleCount) {
}
