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
    /**
     * Built from the injected {@code RestClient.Builder} rather than {@code RestClient.builder()}.
     *
     * <p>They are not the same: Boot's builder comes pre-loaded with the observation registry,
     * a bare one does not. That is the difference between a trace that starts here — where a
     * telemetry reading is actually born — and one that only appears from ingestion onwards,
     * with no record of where the reading came from.
     */
    @Bean
    public RestClient ingestionRestClient(SimulatorProperties properties,
                                          RestClient.Builder builder) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(properties.getIngestionConnectTimeout())
                .withReadTimeout(properties.getIngestionReadTimeout());
        return builder
                .baseUrl(properties.getIngestionBaseUrl())
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();
    }
}
