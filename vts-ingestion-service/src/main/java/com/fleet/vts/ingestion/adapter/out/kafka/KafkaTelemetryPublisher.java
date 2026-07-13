package com.fleet.vts.ingestion.adapter.out.kafka;

import com.fleet.vts.common.event.TelemetryEvent;
import com.fleet.vts.common.topic.Topics;
import com.fleet.vts.ingestion.port.out.TelemetryPublisherPort;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Kafka adapter for the publisher port. Raw telemetry is keyed by vehicleId so
 * all readings for a vehicle land on the same partition (per-vehicle ordering).
 */
@Component
public class KafkaTelemetryPublisher implements TelemetryPublisherPort {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaTelemetryPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publishRaw(TelemetryEvent event) {
        kafkaTemplate.send(Topics.TELEMETRY_RAW, String.valueOf(event.vehicleId()), event);
    }

    @Override
    public void publishDlq(Object payload, String reason) {
        kafkaTemplate.send(Topics.TELEMETRY_DLQ, new DlqRecord(reason, payload, Instant.now()));
    }
}
