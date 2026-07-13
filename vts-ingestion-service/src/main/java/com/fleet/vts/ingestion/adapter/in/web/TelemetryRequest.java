package com.fleet.vts.ingestion.adapter.in.web;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * Inbound HTTP telemetry payload. Bean Validation guards structural validity;
 * {@code ts} is optional and defaults to server time when absent.
 */
public record TelemetryRequest(

        @NotBlank
        String imei,

        Instant ts,

        @NotNull @DecimalMin("-90.0") @DecimalMax("90.0")
        Double lat,

        @NotNull @DecimalMin("-180.0") @DecimalMax("180.0")
        Double lon,

        @Min(0) @Max(400)
        Integer speedKmh,

        @Min(0) @Max(359)
        Integer heading,

        @Min(0) @Max(100)
        Integer battery,

        @Min(0) @Max(100)
        Integer fuelPct,

        Boolean engineOn,

        Boolean ignition,

        @Min(0)
        Long odometerKm,

        String correlationId
) {
}
