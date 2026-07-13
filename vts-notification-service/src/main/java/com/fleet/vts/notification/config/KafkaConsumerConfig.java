package com.fleet.vts.notification.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleet.vts.common.event.GeofenceEvent;
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

/** One typed listener factory per consumed event type (violation, geofence). */
@Configuration
public class KafkaConsumerConfig {

    private <T> ConcurrentKafkaListenerContainerFactory<String, T> factory(
            KafkaProperties properties, ObjectMapper objectMapper, Class<T> type) {
        Map<String, Object> config = properties.buildConsumerProperties(null);
        JsonDeserializer<T> deserializer = new JsonDeserializer<>(type, objectMapper, false);
        deserializer.setUseTypeHeaders(false);
        deserializer.addTrustedPackages("com.fleet.vts.common.event");
        ConsumerFactory<String, T> consumerFactory =
                new DefaultKafkaConsumerFactory<>(config, new StringDeserializer(), deserializer);
        ConcurrentKafkaListenerContainerFactory<String, T> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, ViolationEvent> violationListenerFactory(
            KafkaProperties properties, ObjectMapper objectMapper) {
        return factory(properties, objectMapper, ViolationEvent.class);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, GeofenceEvent> geofenceListenerFactory(
            KafkaProperties properties, ObjectMapper objectMapper) {
        return factory(properties, objectMapper, GeofenceEvent.class);
    }
}
