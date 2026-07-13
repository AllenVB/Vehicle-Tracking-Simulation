package com.fleet.vts.processing.consumer;

import com.fleet.vts.common.event.TelemetryEvent;
import com.fleet.vts.common.topic.Topics;
import com.fleet.vts.processing.processing.TelemetryProcessor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Batch consumer of {@code vehicle.telemetry.raw}. Acknowledges manually only
 * after the whole batch is processed, so a failure re-delivers the batch (the
 * writes are idempotent). Errors propagate to the DefaultErrorHandler.
 */
@Component
public class TelemetryBatchListener {

    private final TelemetryProcessor processor;

    public TelemetryBatchListener(TelemetryProcessor processor) {
        this.processor = processor;
    }

    @KafkaListener(topics = Topics.TELEMETRY_RAW, containerFactory = "kafkaListenerContainerFactory")
    public void onBatch(List<TelemetryEvent> events, Acknowledgment acknowledgment) {
        processor.process(events);
        acknowledgment.acknowledge();
    }
}
