package com.fleet.vts.processing.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleet.vts.common.event.GeofenceEvent;
import com.fleet.vts.common.event.TripEvent;
import com.fleet.vts.common.event.ViolationEvent;
import com.fleet.vts.common.kafka.VtsKafkaConsumers;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;

/**
 * Typed listener factories for persisting stream-analytics outputs (geofence, trip).
 * Deserialization and the retry/dead-letter policy come from vts-common, shared with every
 * other consumer.
 */
@Configuration
public class StreamConsumerConfig {

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, GeofenceEvent> geofenceListenerFactory(
            KafkaProperties properties, ObjectMapper objectMapper, CommonErrorHandler errorHandler) {
        return VtsKafkaConsumers.listenerFactory(properties, objectMapper, GeofenceEvent.class, errorHandler);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TripEvent> tripListenerFactory(
            KafkaProperties properties, ObjectMapper objectMapper, CommonErrorHandler errorHandler) {
        return VtsKafkaConsumers.listenerFactory(properties, objectMapper, TripEvent.class, errorHandler);
    }

    /** For persisting the violations the stream topology produces (they never hit the DB otherwise). */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, ViolationEvent> streamViolationListenerFactory(
            KafkaProperties properties, ObjectMapper objectMapper, CommonErrorHandler errorHandler) {
        return VtsKafkaConsumers.listenerFactory(properties, objectMapper, ViolationEvent.class, errorHandler);
    }
}
