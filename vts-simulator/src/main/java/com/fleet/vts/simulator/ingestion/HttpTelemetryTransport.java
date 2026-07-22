package com.fleet.vts.simulator.ingestion;

import org.springframework.stereotype.Component;

import java.util.List;

/** The original JSON-over-HTTP path, unchanged, now behind the transport interface. */
@Component
public class HttpTelemetryTransport implements TelemetryTransport {

    private final IngestionClient client;

    public HttpTelemetryTransport(IngestionClient client) {
        this.client = client;
    }

    @Override
    public void send(List<TelemetryPayload> readings) {
        client.sendBatch(readings);
    }

    @Override
    public String name() {
        return "HTTP";
    }
}
