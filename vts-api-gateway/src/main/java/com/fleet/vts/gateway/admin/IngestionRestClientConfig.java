package com.fleet.vts.gateway.admin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * The RestClient the DLQ replay uses to re-submit telemetry to ingestion.
 *
 * <p>Only the telemetry DLQ needs it, and only during an admin replay — a rare, deliberate
 * action — so the read timeout is generous relative to the interactive simulator client: a
 * batch re-ingest of a few hundred readings is allowed to take a moment.
 */
@Configuration
public class IngestionRestClientConfig {

    @Bean
    public RestClient ingestionRestClient(
            @Value("${vts.ingestion.base-url:http://localhost:8081}") String baseUrl,
            @Value("${vts.ingestion.connect-timeout:2s}") Duration connectTimeout,
            @Value("${vts.ingestion.read-timeout:30s}") Duration readTimeout,
            RestClient.Builder builder) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(connectTimeout)
                .withReadTimeout(readTimeout);
        return builder
                .baseUrl(baseUrl)
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();
    }
}
