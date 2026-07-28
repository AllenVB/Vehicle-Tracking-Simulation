package com.fleet.vts.iettfeed;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Live data source: periodically polls İETT (İstanbul public buses) real-time
 * vehicle positions and feeds them into the ingestion service as genuine
 * telemetry, replacing the synthetic simulator. Positions are real; the vehicle
 * identity is borrowed from the seeded fleet (IMEIs 001–100).
 */
@SpringBootApplication
@EnableScheduling
public class IettFeedApplication {

    public static void main(String[] args) {
        SpringApplication.run(IettFeedApplication.class, args);
    }
}
