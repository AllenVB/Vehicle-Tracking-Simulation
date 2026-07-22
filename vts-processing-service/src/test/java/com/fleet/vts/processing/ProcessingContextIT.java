package com.fleet.vts.processing;

import com.fleet.vts.processing.consumer.TelemetryBatchListener;
import com.fleet.vts.processing.persistence.TelemetryWriter;
import com.fleet.vts.processing.rules.RuleEngine;
import com.fleet.vts.testsupport.VtsInfra;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots processing against real Postgres, Kafka and Redis.
 *
 * <p>Also asserts that the batch listener really is registered: this service consumes
 * {@code vehicle.telemetry.raw} in batches, and a listener that fails to register is
 * invisible at compile time and silent at runtime — the pipeline just stops persisting.
 */
@SpringBootTest(properties = "management.tracing.sampling.probability=0.0")
class ProcessingContextIT {

    @DynamicPropertySource
    static void infra(DynamicPropertyRegistry registry) {
        VtsInfra.all(registry);
    }

    @Autowired
    private TelemetryBatchListener listener;

    @Autowired
    private TelemetryWriter writer;

    @Autowired
    private RuleEngine ruleEngine;

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    @Test
    void contextLoadsAndListenersAreRegistered() {
        assertThat(listener).isNotNull();
        assertThat(writer).isNotNull();
        assertThat(ruleEngine).isNotNull();
        assertThat(listenerRegistry.getListenerContainers()).isNotEmpty();
    }
}
