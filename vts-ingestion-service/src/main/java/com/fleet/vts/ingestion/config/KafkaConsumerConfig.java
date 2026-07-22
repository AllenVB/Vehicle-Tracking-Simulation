package com.fleet.vts.ingestion.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleet.vts.common.event.DeviceCommandEvent;
import com.fleet.vts.common.kafka.VtsKafkaConsumers;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Ingestion's only consumer: operator commands for the devices it holds sockets to.
 *
 * <p>The group id is unique per process, which is the whole point. A shared group would spread
 * the partitions across instances and hand each command to exactly one of them — most likely
 * not the one holding that device's socket, and the command would go nowhere. A per-instance
 * group turns the topic into a broadcast: everyone hears every command, and the one that can
 * act, does.
 *
 * <p>The cost is a consumer group per restart. Offsets are irrelevant here — a command that
 * was issued while this instance was down is stale by the time it comes back, so the group
 * starts at {@code latest} and never replays.
 */
@Configuration
public class KafkaConsumerConfig {

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, DeviceCommandEvent> deviceCommandListenerFactory(
            KafkaProperties properties, ObjectMapper objectMapper, CommonErrorHandler errorHandler) {

        ConsumerFactory<String, DeviceCommandEvent> shared =
                VtsKafkaConsumers.consumerFactory(properties, objectMapper, DeviceCommandEvent.class);

        Map<String, Object> config = new HashMap<>(shared.getConfigurationProperties());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "vts-ingestion-commands-" + UUID.randomUUID());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");

        ConcurrentKafkaListenerContainerFactory<String, DeviceCommandEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(config,
                shared.getKeyDeserializer(), shared.getValueDeserializer()));
        factory.setCommonErrorHandler(errorHandler);
        VtsKafkaConsumers.enableObservation(factory);
        return factory;
    }
}
