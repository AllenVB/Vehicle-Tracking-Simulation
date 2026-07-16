package com.fleet.vts.simulator.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** Provides the RestClient pointed at ingestion. Kept separate from the
 *  scheduling config so there is no bean cycle (scheduler -> simulator ->
 *  client -> RestClient). */
@Configuration
@EnableConfigurationProperties(SimulatorProperties.class)
public class RestClientConfig {

    /**
     * Timeouts are set explicitly: the underlying JDK request factory defaults to no
     * timeout at all, so an ingestion service that accepts the connection and then
     * never answers would block the calling tick thread forever.
     */
    @Bean
    public RestClient ingestionRestClient(SimulatorProperties properties) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(properties.getIngestionConnectTimeout())
                .withReadTimeout(properties.getIngestionReadTimeout());
        return RestClient.builder()
                .baseUrl(properties.getIngestionBaseUrl())
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();
    }
}
