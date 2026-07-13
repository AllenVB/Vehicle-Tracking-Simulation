package com.fleet.vts.simulator.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** Provides the RestClient pointed at ingestion. Kept separate from the
 *  scheduling config so there is no bean cycle (scheduler -> simulator ->
 *  client -> RestClient). */
@Configuration
@EnableConfigurationProperties(SimulatorProperties.class)
public class RestClientConfig {

    @Bean
    public RestClient ingestionRestClient(SimulatorProperties properties) {
        return RestClient.builder()
                .baseUrl(properties.getIngestionBaseUrl())
                .build();
    }
}
