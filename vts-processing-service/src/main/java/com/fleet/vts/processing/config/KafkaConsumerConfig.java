package com.fleet.vts.processing.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleet.vts.common.event.TelemetryEvent;
import com.fleet.vts.common.kafka.VtsKafkaConsumers;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties.AckMode;

/**
 * Batch consumer wiring: JSON deserialization of {@link TelemetryEvent}, concurrency 8 and
 * manual acknowledgement.
 *
 * <p>The retry/dead-letter policy is no longer declared here — it comes from vts-common's
 * auto-configuration, shared with every other consumer. Exhausted telemetry records still
 * land on {@code vehicle.telemetry.dlq}; that mapping now lives in
 * {@link com.fleet.vts.common.topic.Topics#dlqFor(String)}.
 */
@Configuration
public class KafkaConsumerConfig {

    private static final int CONCURRENCY = 8;

    @Bean
    public ConsumerFactory<String, TelemetryEvent> consumerFactory(KafkaProperties properties,
                                                                   ObjectMapper objectMapper) {
        return VtsKafkaConsumers.consumerFactory(properties, objectMapper, TelemetryEvent.class);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TelemetryEvent> kafkaListenerContainerFactory(
            ConsumerFactory<String, TelemetryEvent> consumerFactory,
            CommonErrorHandler errorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, TelemetryEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setBatchListener(true);
        factory.setConcurrency(CONCURRENCY);
        factory.getContainerProperties().setAckMode(AckMode.MANUAL);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }
}
