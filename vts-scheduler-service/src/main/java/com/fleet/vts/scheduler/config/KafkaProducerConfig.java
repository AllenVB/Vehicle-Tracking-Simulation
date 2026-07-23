package com.fleet.vts.scheduler.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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

    /**
     * JSON serializer for the events this service now produces itself (the maintenance-overdue
     * violation). Built the same way the other services build theirs — JSR-310 aware, no
     * timestamps — so the bytes match what the {@code vehicle.violation} consumers expect. The
     * event is serialised through this and sent over the existing String template, so no second
     * Kafka producer bean is introduced (and the manual-wiring trap with it).
     */
    @Bean
    public ObjectMapper schedulerObjectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

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
