package com.fleet.vts.gateway.live;

import com.fleet.vts.common.event.NotificationEvent;
import com.fleet.vts.common.event.TelemetryEvent;
import com.fleet.vts.common.event.ViolationEvent;
import com.fleet.vts.common.topic.Topics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Kafka -> WebSocket relays. Processed telemetry only updates in-memory state
 * (pushed later as a throttled delta); violations stream live to a topic; and
 * notifications go to the recipient user's private queue.
 */
@Component
public class LiveRelays {

    private final LiveMapState state;
    private final SimpMessagingTemplate messaging;

    public LiveRelays(LiveMapState state, SimpMessagingTemplate messaging) {
        this.state = state;
        this.messaging = messaging;
    }

    @KafkaListener(topics = Topics.TELEMETRY_PROCESSED, containerFactory = "processedTelemetryFactory")
    public void onTelemetry(TelemetryEvent e) {
        state.update(new Position(e.vehicleId(), e.lat(), e.lon(), e.speedKmh(), e.heading(),
                e.fuelPct(), e.ts()));
    }

    @KafkaListener(topics = Topics.VIOLATION, containerFactory = "violationStreamFactory")
    public void onViolation(ViolationEvent e) {
        messaging.convertAndSend("/topic/violations", e);
    }

    @KafkaListener(topics = Topics.NOTIFICATION, containerFactory = "notificationStreamFactory")
    public void onNotification(NotificationEvent e) {
        if (e.userId() != null) {
            messaging.convertAndSendToUser(String.valueOf(e.userId()), "/queue/notifications", e);
        }
    }
}
