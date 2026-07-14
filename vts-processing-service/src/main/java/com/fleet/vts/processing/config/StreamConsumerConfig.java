package com.fleet.vts.processing.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleet.vts.common.event.GeofenceEvent;
import com.fleet.vts.common.event.TripEvent;
import com.fleet.vts.common.event.ViolationEvent;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.Map;

/** Typed listener factories for persisting stream-analytics outputs (geofence, trip). */
@Configuration
public class StreamConsumerConfig {

    private <T> ConcurrentKafkaListenerContainerFactory<String, T> factory(
            KafkaProperties properties, ObjectMapper objectMapper, Class<T> type) {
        Map<String, Object> config = properties.buildConsumerProperties(null);
        JsonDeserializer<T> deserializer = new JsonDeserializer<>(type, objectMapper, false);
        deserializer.setUseTypeHeaders(false);
        deserializer.addTrustedPackages("com.fleet.vts.common.event");
        ConsumerFactory<String, T> cf =
                new DefaultKafkaConsumerFactory<>(config, new StringDeserializer(), deserializer);
        ConcurrentKafkaListenerContainerFactory<String, T> f = new ConcurrentKafkaListenerContainerFactory<>();
        f.setConsumerFactory(cf);
        return f;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, GeofenceEvent> geofenceListenerFactory(
            KafkaProperties properties, ObjectMapper objectMapper) {
        return factory(properties, objectMapper, GeofenceEvent.class);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TripEvent> tripListenerFactory(
            KafkaProperties properties, ObjectMapper objectMapper) {
        return factory(properties, objectMapper, TripEvent.class);
    }

    /** For persisting the violations the stream topology produces (they never hit the DB otherwise). */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, ViolationEvent> streamViolationListenerFactory(
            KafkaProperties properties, ObjectMapper objectMapper) {
        return factory(properties, objectMapper, ViolationEvent.class);
    }
}
