package com.fleet.vts.gateway.live;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleet.vts.common.event.NotificationEvent;
import com.fleet.vts.common.event.TelemetryEvent;
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

/** Typed Kafka listener factories for the live-push consumers. */
@Configuration
public class LiveKafkaConfig {

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
    public ConcurrentKafkaListenerContainerFactory<String, TelemetryEvent> processedTelemetryFactory(
            KafkaProperties properties, ObjectMapper objectMapper) {
        return factory(properties, objectMapper, TelemetryEvent.class);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, ViolationEvent> violationStreamFactory(
            KafkaProperties properties, ObjectMapper objectMapper) {
        return factory(properties, objectMapper, ViolationEvent.class);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, NotificationEvent> notificationStreamFactory(
            KafkaProperties properties, ObjectMapper objectMapper) {
        return factory(properties, objectMapper, NotificationEvent.class);
    }
}
