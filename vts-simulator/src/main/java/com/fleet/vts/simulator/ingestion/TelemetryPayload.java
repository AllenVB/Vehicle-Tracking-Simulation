package com.fleet.vts.simulator.ingestion;

import java.time.Instant;

/**
 * Outbound telemetry JSON posted to ingestion. Field names mirror the ingestion
 * service's request contract; the simulator deliberately does not depend on that
 * module (loose coupling over the HTTP boundary).
 */
public record TelemetryPayload(
        String imei,
        Instant ts,
        double lat,
        double lon,
        int speedKmh,
        int heading,
        int battery,
        int fuelPct,
        boolean engineOn,
        boolean ignition,
        long odometerKm,
        String correlationId
) {
}
