package com.fleet.vts.processing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Processing service. Batch-consumes raw telemetry, performs batch JDBC inserts
 * into the TimescaleDB hypertable, evaluates stateless rules and writes the
 * outbox in the same transaction.
 */
@SpringBootApplication
public class ProcessingApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProcessingApplication.class, args);
    }
}
