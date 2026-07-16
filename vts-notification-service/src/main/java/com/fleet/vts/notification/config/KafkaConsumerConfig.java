package com.fleet.vts.notification.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleet.vts.common.event.GeofenceEvent;
import com.fleet.vts.common.event.ViolationEvent;
import com.fleet.vts.common.kafka.VtsKafkaConsumers;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;

/**
 * One typed listener factory per consumed event type (violation, geofence). Deserialization
 * and the retry/dead-letter policy come from vts-common, shared with every other consumer.
 */
@Configuration
public class KafkaConsumerConfig {

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, ViolationEvent> violationListenerFactory(
            KafkaProperties properties, ObjectMapper objectMapper, CommonErrorHandler errorHandler) {
        return VtsKafkaConsumers.listenerFactory(properties, objectMapper, ViolationEvent.class, errorHandler);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, GeofenceEvent> geofenceListenerFactory(
            KafkaProperties properties, ObjectMapper objectMapper, CommonErrorHandler errorHandler) {
        return VtsKafkaConsumers.listenerFactory(properties, objectMapper, GeofenceEvent.class, errorHandler);
    }
}
