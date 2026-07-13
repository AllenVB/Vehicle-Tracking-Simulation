package com.fleet.vts.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Notification service. Consumes violation and geofence events and dispatches
 * notifications through pluggable {@code NotificationSender} strategies with
 * per-rule cooldown and quiet-hours handling.
 */
@SpringBootApplication
public class NotificationApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationApplication.class, args);
    }
}
