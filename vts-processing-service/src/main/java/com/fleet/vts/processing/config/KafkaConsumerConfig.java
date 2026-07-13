package com.fleet.vts.processing.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleet.vts.common.event.TelemetryEvent;
import com.fleet.vts.common.topic.Topics;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.Map;

/**
 * Batch consumer wiring: JSON deserialization of {@link TelemetryEvent} (with a
 * JSR-310-aware ObjectMapper), concurrency 8, manual acknowledgement, and a
 * DefaultErrorHandler with exponential backoff that dead-letters exhausted
 * records to {@code vehicle.telemetry.dlq}.
 */
@Configuration
public class KafkaConsumerConfig {

    private static final int CONCURRENCY = 8;

    @Bean
    public ConsumerFactory<String, TelemetryEvent> consumerFactory(KafkaProperties properties,
                                                                   ObjectMapper objectMapper) {
        Map<String, Object> config = properties.buildConsumerProperties(null);
        JsonDeserializer<TelemetryEvent> valueDeserializer =
                new JsonDeserializer<>(TelemetryEvent.class, objectMapper, false);
        valueDeserializer.setUseTypeHeaders(false);
        valueDeserializer.addTrustedPackages("com.fleet.vts.common.event");
        return new DefaultKafkaConsumerFactory<>(config, new StringDeserializer(), valueDeserializer);
    }

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, Object> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, ex) -> new TopicPartition(Topics.TELEMETRY_DLQ, record.partition()));
        // Exponential backoff (1s, 2s, 4s ...) bounded to ~2 min, then DLQ.
        ExponentialBackOff backOff = new ExponentialBackOff(1000L, 2.0);
        backOff.setMaxInterval(60_000L);
        backOff.setMaxElapsedTime(120_000L);
        return new DefaultErrorHandler(recoverer, backOff);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TelemetryEvent> kafkaListenerContainerFactory(
            ConsumerFactory<String, TelemetryEvent> consumerFactory,
            DefaultErrorHandler errorHandler) {
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
