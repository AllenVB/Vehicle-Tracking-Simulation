package com.fleet.vts.gateway.live;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * The RestClient the operator-control endpoints proxy through to the simulator.
 *
 * <p>The timeouts are the point of this class. {@code RestClient.create(url)} uses the
 * JDK request factory's defaults, which impose no read timeout: a simulator that accepts
 * the connection and then stalls would hold the servlet thread serving the operator's
 * request open indefinitely, and enough of those exhaust the gateway's thread pool —
 * taking down the live map along with the control endpoint that hung.
 *
 * <p>Timeouts are short on purpose. This proxies an interactive click, so failing fast
 * with an error the operator can retry beats a request that appears to hang.
 */
@Configuration
public class SimulatorRestClientConfig {

    @Bean
    public RestClient simulatorRestClient(@Value("${vts.simulator.base-url}") String baseUrl,
                                          @Value("${vts.simulator.connect-timeout:2s}") Duration connectTimeout,
                                          @Value("${vts.simulator.read-timeout:5s}") Duration readTimeout,
                                          RestClient.Builder builder) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(connectTimeout)
                .withReadTimeout(readTimeout);
        // Boot'un builder'ı: gözlem kaydı yüklü gelir, çıplak RestClient.builder() gelmez —
        // operatörün taşıma isteği de böylece ize dahil olur.
        return builder
                .baseUrl(baseUrl)
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();
    }
}
