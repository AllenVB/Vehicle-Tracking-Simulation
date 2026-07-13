package com.fleet.vts.notification.listener;

import com.fleet.vts.common.event.GeofenceEvent;
import com.fleet.vts.common.event.ViolationEvent;
import com.fleet.vts.common.topic.Topics;
import com.fleet.vts.notification.service.NotificationService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Consumes violations and geofence events and hands them to the service. */
@Component
public class NotificationListeners {

    private final NotificationService service;

    public NotificationListeners(NotificationService service) {
        this.service = service;
    }

    @KafkaListener(topics = Topics.VIOLATION, containerFactory = "violationListenerFactory")
    public void onViolation(ViolationEvent event) {
        service.onViolation(event);
    }

    @KafkaListener(topics = Topics.GEOFENCE_EVENT, containerFactory = "geofenceListenerFactory")
    public void onGeofence(GeofenceEvent event) {
        service.onGeofence(event);
    }
}
