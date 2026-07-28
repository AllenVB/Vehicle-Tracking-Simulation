package com.fleet.vts.iettfeed.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Two RestClients: one to the İETT SOAP endpoint (slow third party → longer read
 * timeout) and one to the ingestion service. Both are built from the injected
 * {@code RestClient.Builder} so Boot's observation registry is wired in and the
 * telemetry trace starts here, at the source.
 */
@Configuration
@EnableConfigurationProperties(IettProperties.class)
public class RestClientConfig {

    @Bean
    public RestClient iettRestClient(IettProperties props, RestClient.Builder builder) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(props.getIettConnectTimeout())
                .withReadTimeout(props.getIettReadTimeout());
        return builder
                .baseUrl(props.getBaseUrl())
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();
    }

    @Bean
    public RestClient ingestionRestClient(IettProperties props, RestClient.Builder builder) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(props.getIngestionConnectTimeout())
                .withReadTimeout(props.getIngestionReadTimeout());
        return builder
                .baseUrl(props.getIngestionBaseUrl())
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();
    }
}
