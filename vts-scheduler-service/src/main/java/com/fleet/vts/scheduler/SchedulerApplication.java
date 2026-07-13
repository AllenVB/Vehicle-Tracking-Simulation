package com.fleet.vts.scheduler;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Scheduler service. Runs ShedLock-guarded periodic jobs: device-offline
 * detection, daily driver scoring, maintenance reminders and the outbox
 * publisher fallback.
 */
@SpringBootApplication
public class SchedulerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SchedulerApplication.class, args);
    }
}
