package com.fleet.vts.ingestion;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Stateless ingestion service. Exposes the HTTP telemetry adapter and the raw-TCP device
 * channel behind one {@code TelemetryInboundPort}, and publishes validated telemetry to Kafka.
 *
 * <p>Scheduling is on for a single job: closing out device commands nobody answered. It lives
 * here rather than in the scheduler service because the fact it settles — whether a socket was
 * ever there — is knowledge only this service has.
 */
@SpringBootApplication
@EnableScheduling
public class IngestionApplication {

    public static void main(String[] args) {
        SpringApplication.run(IngestionApplication.class, args);
    }
}
