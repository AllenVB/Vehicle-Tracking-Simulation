package com.fleet.vts.ingestion;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Stateless ingestion service. Exposes the HTTP telemetry adapter behind a
 * {@code TelemetryInboundPort} and publishes validated telemetry to Kafka.
 */
@SpringBootApplication
public class IngestionApplication {

    public static void main(String[] args) {
        SpringApplication.run(IngestionApplication.class, args);
    }
}
