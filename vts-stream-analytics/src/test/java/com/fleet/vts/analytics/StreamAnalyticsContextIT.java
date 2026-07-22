package com.fleet.vts.analytics;

import com.fleet.vts.common.topic.Topics;
import com.fleet.vts.testsupport.VtsContainers;
import com.fleet.vts.testsupport.VtsInfra;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.streams.KafkaStreams;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.annotation.KafkaStreamsDefaultConfiguration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the analytics service and lets Kafka Streams actually start.
 *
 * <p>Starting matters: the topology is assembled by an {@code @Autowired} method, but state
 * stores are only named, wired and validated when {@code KafkaStreams} builds and starts it.
 * A duplicate store name or a source topic nobody creates is invisible until then — and the
 * {@code TopologyTestDriver} unit tests exercise rules, not startup.
 *
 * <p>The source topic is created with a single partition. Twenty-four is a production
 * ordering guarantee; here it would mean 24 tasks x 5 stores worth of RocksDB for a question
 * that one partition answers just as well.
 */
@SpringBootTest(properties = "management.tracing.sampling.probability=0.0")
class StreamAnalyticsContextIT {

    @DynamicPropertySource
    static void infra(DynamicPropertyRegistry registry) {
        VtsInfra.postgres(registry);
        VtsInfra.kafka(registry);
        // Each run gets its own application id so a reused broker does not hand this test
        // the previous run's consumer group and changelog topics.
        registry.add("spring.kafka.streams.application-id",
                () -> "vts-stream-analytics-it-" + System.nanoTime());
        registry.add("spring.kafka.streams.properties.num.stream.threads", () -> "1");
    }

    @BeforeAll
    static void createSourceTopic() throws Exception {
        try (Admin admin = Admin.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, VtsContainers.kafkaBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(Topics.TELEMETRY_RAW, 1, (short) 1)))
                    .all().get();
        } catch (Exception e) {
            if (!(e.getCause() instanceof org.apache.kafka.common.errors.TopicExistsException)) {
                throw e;
            }
        }
    }

    @Autowired
    @Qualifier(KafkaStreamsDefaultConfiguration.DEFAULT_STREAMS_BUILDER_BEAN_NAME)
    private StreamsBuilderFactoryBean streams;

    @Test
    void topologyStartsAndReachesRunning() {
        KafkaStreams kafkaStreams = streams.getKafkaStreams();
        assertThat(kafkaStreams).isNotNull();

        Awaitility.await().atMost(Duration.ofSeconds(60))
                .until(() -> kafkaStreams.state() == KafkaStreams.State.RUNNING);

        assertThat(streams.getTopology().describe().subtopologies()).isNotEmpty();
    }
}
