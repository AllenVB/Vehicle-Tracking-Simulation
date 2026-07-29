package com.fleet.vts.gateway.track;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * A single GPS fix posted by the in-browser phone tracker.
 *
 * <p>Field names mirror ingestion's {@code TelemetryRequest} exactly, because this record is
 * forwarded verbatim (as JSON) to the ingestion HTTP endpoint — the gateway is only a public,
 * auth-free door for a real device so the phone talks to one origin (no CORS, no JWT). The
 * phone supplies real GPS values: {@code speedKmh} from {@code coords.speed} (m/s converted),
 * {@code heading} from {@code coords.heading}, {@code accuracy} from {@code coords.accuracy}.
 */
public record TrackRequest(

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

        @DecimalMin("0.0")
        Double accuracy,

        @Min(0) @Max(100)
        Integer battery
) {
}
