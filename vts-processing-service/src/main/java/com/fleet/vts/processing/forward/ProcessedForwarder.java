package com.fleet.vts.processing.forward;

import com.fleet.vts.common.event.TelemetryEvent;
import com.fleet.vts.common.topic.Topics;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Forwards processed telemetry to {@code vehicle.telemetry.processed} (keyed by
 * vehicleId) for UI fan-out by the gateway.
 */
@Component
public class ProcessedForwarder {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public ProcessedForwarder(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void forward(List<TelemetryEvent> events) {
        for (TelemetryEvent e : events) {
            kafkaTemplate.send(Topics.TELEMETRY_PROCESSED, String.valueOf(e.vehicleId()), e);
        }
    }
}
