package com.fleet.vts.ingestion.adapter.in.web;

import com.fleet.vts.ingestion.port.in.BatchIngestResult;
import com.fleet.vts.ingestion.port.in.IngestResult;
import com.fleet.vts.ingestion.port.in.TelemetryInboundPort;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Primary HTTP adapter. Thin: it only maps HTTP to the inbound port. Adding a
 * different transport (e.g. MQTT) means adding another adapter, not changing
 * the application core.
 */
@RestController
@RequestMapping("/api/v1/telemetry")
public class HttpTelemetryAdapter {

    private final TelemetryInboundPort inbound;

    public HttpTelemetryAdapter(TelemetryInboundPort inbound) {
        this.inbound = inbound;
    }

    /** Single reading. Structurally invalid bodies are rejected with 400. */
    @PostMapping
    public ResponseEntity<IngestResult> ingest(@Valid @RequestBody TelemetryRequest request) {
        return ResponseEntity.accepted().body(inbound.ingestOne(request));
    }

    /**
     * Batch endpoint. Each element is validated individually inside the core;
     * bad elements are dead-lettered so a single bad reading cannot fail the
     * whole batch. Returns a per-batch accepted/dead-lettered summary.
     */
    @PostMapping("/batch")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.ACCEPTED)
    public BatchIngestResult ingestBatch(@RequestBody List<TelemetryRequest> requests) {
        return inbound.ingestBatch(requests);
    }
}
