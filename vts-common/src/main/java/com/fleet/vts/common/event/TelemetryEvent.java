package com.fleet.vts.common.event;

import lombok.Builder;

import java.time.Instant;

/**
 * Canonical telemetry event on {@code vehicle.telemetry.raw} (and
 * {@code vehicle.telemetry.processed}). Ingestion resolves the device IMEI to a
 * vehicle and publishes this keyed by {@code vehicleId} to preserve per-vehicle
 * ordering.
 *
 * <p>A record so it stays immutable and serializer-agnostic: Jackson uses the
 * canonical constructor today; swapping to Avro later touches only the
 * serializer, not call sites. {@code correlationId} threads through every stage
 * for tracing.
 */
@Builder
public record TelemetryEvent(
        Long tenantId,
        Long vehicleId,
        Long deviceId,
        String imei,
        Instant ts,
        Double lat,
        Double lon,
        Integer speedKmh,
        Integer heading,
        Integer battery,
        Integer fuelPct,
        Boolean engineOn,
        Boolean ignition,
        Long odometerKm,
        String correlationId
) {
}
