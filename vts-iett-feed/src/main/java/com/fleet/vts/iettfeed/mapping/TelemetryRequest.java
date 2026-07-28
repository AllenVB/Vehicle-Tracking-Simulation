package com.fleet.vts.iettfeed.mapping;

import java.time.Instant;

/**
 * Outbound payload matching the ingestion service's {@code TelemetryRequest}
 * contract (field names must line up exactly). Optional fields are left
 * {@code null} when unknown; ingestion validates each element and stamps missing
 * {@code ts}. Serialized as ISO-8601 for {@code ts} by the shared ObjectMapper.
 */
public record TelemetryRequest(
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
        String correlationId) {
}
