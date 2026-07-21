package com.fleet.vts.gateway.live;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.Map;

/**
 * The gateway publishes no events of its own; this producer exists so the shared error
 * handler has something to dead-letter a poison record with. Without it the relays in
 * {@link LiveRelays} would have no way off a record they cannot deserialize.
 */
@Configuration
public class LiveKafkaProducerConfig {

    @Bean
    public ProducerFactory<String, Object> producerFactory(KafkaProperties properties,
                                                           ObjectMapper objectMapper) {
        Map<String, Object> config = properties.buildProducerProperties(null);
        DefaultKafkaProducerFactory<String, Object> factory = new DefaultKafkaProducerFactory<>(config);
        factory.setKeySerializer(new StringSerializer());
        JsonSerializer<Object> valueSerializer = new JsonSerializer<>(objectMapper);
        valueSerializer.setAddTypeInfo(false);
        factory.setValueSerializer(valueSerializer);
        return factory;
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> producerFactory) {
        KafkaTemplate<String, Object> template = new KafkaTemplate<>(producerFactory);
        // Uretici, iz kimligini mesaj basligina yazar. Bu template elle kuruldugu icin
        // spring.kafka.template.observation-enabled ona ulasmaz; acikca acilmazsa tuketici
        // devam ettirecek bir iz bulamaz ve zincir Kafka'da kopar.
        template.setObservationEnabled(true);
        return template;
    }
}
