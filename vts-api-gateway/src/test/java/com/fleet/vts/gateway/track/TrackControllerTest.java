package com.fleet.vts.gateway.track;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * The public phone-tracker ingress forwards each GPS fix verbatim to ingestion's batch endpoint
 * (as a one-element array), carrying the real speed and GPS accuracy through. When a driver-app
 * phone supplies a session token that has been taken over by another device, the fix is refused
 * with 409 and never forwarded — so the losing phone learns to stop.
 */
class TrackControllerTest {

    private final TrackRequest fix = new TrackRequest(
            "990000000000001", null, 41.015, 28.979, 50, 90, 8.0, null);

    @Test
    void forwardsFixToIngestionBatchAsSingletonArray() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DriverSessionService sessions = mock(DriverSessionService.class);
        TrackController controller = new TrackController(builder.build(), sessions);

        server.expect(requestTo("/api/v1/telemetry/batch"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$[0].imei").value("990000000000001"))
                .andExpect(jsonPath("$[0].lat").value(41.015))
                .andExpect(jsonPath("$[0].speedKmh").value(50))
                .andExpect(jsonPath("$[0].accuracy").value(8.0))
                .andRespond(withStatus(HttpStatus.ACCEPTED));

        // Legacy QR tracker sends no session header: forwarded unchanged.
        ResponseEntity<Void> res = controller.ingest(fix, null);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        server.verify();
    }

    @Test
    void rejectsWithConflictWhenAnotherDeviceTookOverTheSession() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DriverSessionService sessions = mock(DriverSessionService.class);
        when(sessions.refresh("stale-token")).thenReturn(false);
        TrackController controller = new TrackController(builder.build(), sessions);

        ResponseEntity<Void> res = controller.ingest(fix, "stale-token");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        server.verify();   // no forward was attempted
    }
}
