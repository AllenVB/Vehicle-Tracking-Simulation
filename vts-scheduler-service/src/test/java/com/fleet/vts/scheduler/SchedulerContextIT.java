package com.fleet.vts.scheduler;

import com.fleet.vts.testsupport.VtsInfra;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the scheduler against real Postgres and Kafka.
 *
 * <p>This service is the reason the whole context-test set exists. It is the only one that
 * produces without consuming, so it holds a {@code KafkaTemplate<String, String>} while the
 * others hold {@code <String, Object>}. When vts-common's shared error handler asked for the
 * latter, {@code @ConditionalOnBean} matched the raw type, the generic injection did not,
 * and the service restart-looped 57 times in production — with a green build and green unit
 * tests, because nothing ever started a context.
 *
 * <p>Asserting the two beans by type is what makes the test bite: a context that merely
 * "loads" would still pass if the recoverer silently disappeared.
 */
@SpringBootTest(properties = {
        // Tracing wiring stays in scope (that is where the manual templates live); only the
        // export is turned off, since no collector is running.
        "management.tracing.sampling.probability=0.0"
})
class SchedulerContextIT {

    @DynamicPropertySource
    static void infra(DynamicPropertyRegistry registry) {
        VtsInfra.postgres(registry);
        VtsInfra.kafka(registry);
    }

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private DefaultErrorHandler errorHandler;

    @Test
    void contextLoadsWithProducerOnlyKafkaTemplate() {
        assertThat(kafkaTemplate).isNotNull();
        assertThat(errorHandler).isNotNull();
    }
}
