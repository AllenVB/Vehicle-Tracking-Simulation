package com.fleet.vts.simulator.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Posts telemetry batches to the ingestion service. Failures are logged and
 * swallowed: the simulator keeps running even if ingestion is briefly down.
 */
@Component
public class IngestionClient {

    private static final Logger log = LoggerFactory.getLogger(IngestionClient.class);
    private static final String BATCH_PATH = "/api/v1/telemetry/batch";

    private final RestClient restClient;

    public IngestionClient(RestClient ingestionRestClient) {
        this.restClient = ingestionRestClient;
    }

    public void sendBatch(List<?> readings) {
        if (readings.isEmpty()) {
            return;
        }
        try {
            restClient.post()
                    .uri(BATCH_PATH)
                    .body(readings)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("Ingestion batch POST failed ({} readings): {}", readings.size(), e.getMessage());
        }
    }
}
