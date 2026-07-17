package com.fleet.vts.analytics.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafkaStreams;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables the Kafka Streams topology and provides a JSR-310-aware ObjectMapper
 * (this is a non-web module, so Spring Boot does not build one for us).
 */
@Configuration
@EnableKafkaStreams
@EnableScheduling   // VehicleRuleRegistry re-resolves rule applicability/thresholds periodically
public class AnalyticsConfig {

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
