package com.fleet.vts.notification.sender;

import com.fleet.vts.common.event.NotificationEvent;
import com.fleet.vts.common.enums.NotificationChannel;
import com.fleet.vts.common.topic.Topics;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Real WebSocket delivery. Publishes a {@link NotificationEvent} to
 * {@code vehicle.notification}; the API gateway relays it to the user's private
 * STOMP queue. Keeps the gateway the single owner of client WebSocket sessions.
 */
@Component
public class WebSocketNotificationSender implements NotificationSender {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public WebSocketNotificationSender(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.WEBSOCKET;
    }

    @Override
    public boolean send(NotificationMessage m) {
        NotificationEvent event = NotificationEvent.builder()
                .tenantId(m.tenantId())
                .userId(m.userId())
                .driverId(m.driverId())
                .vehicleId(m.vehicleId())
                .ruleCode(m.ruleCode())
                .severity(m.severity())
                .channel(NotificationChannel.WEBSOCKET)
                .title(m.title())
                .body(m.body())
                .occurredAt(m.occurredAt())
                .sourceViolationId(m.sourceViolationId())
                .correlationId(UUID.randomUUID().toString())
                .build();
        String key = m.userId() != null ? String.valueOf(m.userId()) : String.valueOf(m.vehicleId());
        kafkaTemplate.send(Topics.NOTIFICATION, key, event);
        return true;
    }
}
