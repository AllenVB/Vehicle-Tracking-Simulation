package com.fleet.vts.scheduler.config;

import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/** String producer used by the outbox publisher (payloads are stored JSON). */
@Configuration
public class KafkaProducerConfig {

    @Bean
    public ProducerFactory<String, String> producerFactory(KafkaProperties properties) {
        return new DefaultKafkaProducerFactory<>(properties.buildProducerProperties(null),
                new StringSerializer(), new StringSerializer());
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> pf) {
        KafkaTemplate<String, String> template = new KafkaTemplate<>(pf);
        // Uretici, iz kimligini mesaj basligina yazar. Bu template elle kuruldugu icin
        // spring.kafka.template.observation-enabled ona ulasmaz; acikca acilmazsa tuketici
        // devam ettirecek bir iz bulamaz ve zincir Kafka'da kopar.
        template.setObservationEnabled(true);
        return template;
    }
}
