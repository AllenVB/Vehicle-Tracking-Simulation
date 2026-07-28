package com.fleet.vts.iettfeed.ingest;

import com.fleet.vts.iettfeed.mapping.TelemetryRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Posts telemetry batches to the ingestion service's batch endpoint. Failures are
 * logged and swallowed so a briefly-unavailable ingestion never crashes the feed.
 */
@Component
public class IngestionClient {

    private static final Logger log = LoggerFactory.getLogger(IngestionClient.class);
    private static final String BATCH_PATH = "/api/v1/telemetry/batch";

    private final RestClient restClient;

    public IngestionClient(RestClient ingestionRestClient) {
        this.restClient = ingestionRestClient;
    }

    /** @return true if the batch was accepted (2xx), false on empty or failure. */
    public boolean sendBatch(List<TelemetryRequest> readings) {
        if (readings.isEmpty()) {
            return false;
        }
        try {
            restClient.post()
                    .uri(BATCH_PATH)
                    .body(readings)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("Ingestion batch POST başarısız ({} okuma): {}", readings.size(), e.getMessage());
            return false;
        }
    }
}
