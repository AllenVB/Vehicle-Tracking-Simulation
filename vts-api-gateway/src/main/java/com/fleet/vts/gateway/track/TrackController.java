package com.fleet.vts.gateway.track;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Public, auth-free ingress for a real in-browser GPS tracker (the phone).
 *
 * <p>Browsers only expose the Geolocation API in a secure context and only to same-origin (no
 * CORS preflight hassle), so the phone page is served by the gateway and posts here — one
 * origin, one port to tunnel. This endpoint is intentionally unauthenticated (see
 * {@code SecurityConfig}): a field GPS device cannot carry a user's JWT. It forwards each fix
 * to ingestion's batch endpoint, which resolves the IMEI to a vehicle and dead-letters an
 * unknown device rather than failing the request.
 *
 * <p>Forwarding to {@code /batch} (with a one-element list) rather than the single-reading
 * endpoint is deliberate: batch never 400s on a bad reading, so a momentary bad fix from the
 * phone can never surface to the device as an error.
 */
@RestController
@RequestMapping("/api/v1/track")
public class TrackController {

    private static final String INGEST_BATCH_PATH = "/api/v1/telemetry/batch";

    private final RestClient ingestion;

    public TrackController(RestClient ingestionRestClient) {
        this.ingestion = ingestionRestClient;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void ingest(@Valid @RequestBody TrackRequest request) {
        ingestion.post()
                .uri(INGEST_BATCH_PATH)
                .body(List.of(request))
                .retrieve()
                .toBodilessEntity();
    }
}
