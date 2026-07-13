package com.fleet.vts.analytics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Stream analytics service. Runs the Kafka Streams topology that evaluates the
 * stateful rules (harsh braking, sustained speeding, idling, geofencing, trips).
 */
@SpringBootApplication
public class StreamAnalyticsApplication {

    public static void main(String[] args) {
        SpringApplication.run(StreamAnalyticsApplication.class, args);
    }
}
