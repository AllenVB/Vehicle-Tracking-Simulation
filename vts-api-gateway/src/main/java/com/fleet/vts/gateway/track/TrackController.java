package com.fleet.vts.gateway.track;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
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
    private final DriverSessionService sessions;

    public TrackController(RestClient ingestionRestClient, DriverSessionService sessions) {
        this.ingestion = ingestionRestClient;
        this.sessions = sessions;
    }

    /**
     * A driver-app phone echoes its {@code X-Device-Session} token here on every fix: it both
     * refreshes the vehicle's single-driver lease and detects a takeover — if another device has
     * since signed in, {@link DriverSessionService#refresh} returns false and we answer 409 so the
     * phone stops streaming and re-prompts. The legacy QR tracker sends no such header; then the
     * check is skipped and the fix is forwarded exactly as before (fully public, unchanged).
     */
    @PostMapping
    public ResponseEntity<Void> ingest(
            @Valid @RequestBody TrackRequest request,
            @RequestHeader(value = "X-Device-Session", required = false) String session) {
        if (session != null && !session.isBlank() && !sessions.refresh(session)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        ingestion.post()
                .uri(INGEST_BATCH_PATH)
                .body(List.of(request))
                .retrieve()
                .toBodilessEntity();
        return ResponseEntity.accepted().build();
    }

    /**
     * Store-and-forward drain: a driver-app phone that was offline (tunnel, dead zone) accumulates
     * fixes locally and flushes them here as a batch when the network returns. Deliberately NOT
     * {@code @Valid} — like ingestion's own batch endpoint, one bad reading must not fail the whole
     * packet; ingestion validates each element and dead-letters the bad ones. The session is checked
     * once for the packet, so a taken-over phone is told to stop before its backlog is forwarded.
     */
    @PostMapping("/batch")
    public ResponseEntity<Void> ingestBatch(
            @RequestBody List<TrackRequest> fixes,
            @RequestHeader(value = "X-Device-Session", required = false) String session) {
        if (session != null && !session.isBlank() && !sessions.refresh(session)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        if (fixes == null || fixes.isEmpty()) {
            return ResponseEntity.accepted().build();
        }
        ingestion.post()
                .uri(INGEST_BATCH_PATH)
                .body(fixes)
                .retrieve()
                .toBodilessEntity();
        return ResponseEntity.accepted().build();
    }
}
