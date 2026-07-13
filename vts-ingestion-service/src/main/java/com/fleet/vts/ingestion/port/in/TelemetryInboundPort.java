package com.fleet.vts.ingestion.port.in;

import com.fleet.vts.ingestion.adapter.in.web.TelemetryRequest;

import java.util.List;

/**
 * Primary (driving) port for telemetry ingestion. HTTP is one adapter today;
 * an MQTT adapter can be added later without touching the application core.
 */
public interface TelemetryInboundPort {

    /** Ingest a single reading. Unresolvable devices are dead-lettered. */
    IngestResult ingestOne(TelemetryRequest request);

    /** Ingest a batch; each element is validated and routed independently. */
    BatchIngestResult ingestBatch(List<TelemetryRequest> requests);
}
