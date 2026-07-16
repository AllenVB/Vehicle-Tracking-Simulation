package com.fleet.vts.gateway.live;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleet.vts.common.event.NotificationEvent;
import com.fleet.vts.common.event.TelemetryEvent;
import com.fleet.vts.common.event.ViolationEvent;
import com.fleet.vts.common.kafka.VtsKafkaConsumers;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;

/**
 * Typed Kafka listener factories for the live-push consumers. Deserialization and the
 * retry/dead-letter policy come from vts-common, shared with every other consumer.
 */
@Configuration
public class LiveKafkaConfig {

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TelemetryEvent> processedTelemetryFactory(
            KafkaProperties properties, ObjectMapper objectMapper, CommonErrorHandler errorHandler) {
        return VtsKafkaConsumers.listenerFactory(properties, objectMapper, TelemetryEvent.class, errorHandler);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, ViolationEvent> violationStreamFactory(
            KafkaProperties properties, ObjectMapper objectMapper, CommonErrorHandler errorHandler) {
        return VtsKafkaConsumers.listenerFactory(properties, objectMapper, ViolationEvent.class, errorHandler);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, NotificationEvent> notificationStreamFactory(
            KafkaProperties properties, ObjectMapper objectMapper, CommonErrorHandler errorHandler) {
        return VtsKafkaConsumers.listenerFactory(properties, objectMapper, NotificationEvent.class, errorHandler);
    }
}
