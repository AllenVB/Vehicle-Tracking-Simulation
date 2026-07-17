package com.fleet.vts.common.kafka;

import com.fleet.vts.common.topic.Topics;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * The shared Kafka consumer error policy: retry with exponential backoff, then dead-letter.
 *
 * <p>Previously only the processing service had this, leaving the gateway's and the
 * notification service's listeners with the container default — retry the same record
 * forever, stalling the partition behind it. Providing it as an auto-configuration means a
 * service gets the policy by depending on vts-common, rather than by remembering to
 * re-declare it.
 *
 * <p>Dead letters are routed by {@link Topics#dlqFor(String)}, so one handler serves every
 * topic. The record keeps its original partition, which preserves per-vehicle ordering on
 * the DLQ for anyone replaying it.
 */
@AutoConfiguration(after = KafkaAutoConfiguration.class)
@ConditionalOnClass(KafkaTemplate.class)
public class VtsKafkaErrorHandlingAutoConfiguration {

    /** Retry for ~2 minutes (1s, 2s, 4s ... capped at 60s), then give up and dead-letter. */
    private static final long INITIAL_BACKOFF_MILLIS = 1_000L;
    private static final long MAX_BACKOFF_MILLIS = 60_000L;
    private static final long MAX_ELAPSED_MILLIS = 120_000L;
    private static final double BACKOFF_MULTIPLIER = 2.0;

    /**
     * Requires a {@code KafkaTemplate} to publish with; a service without one has no producer
     * and so cannot dead-letter. {@link ConditionalOnMissingBean} lets a service override the
     * policy with its own bean.
     *
     * <p>The template is taken as {@code KafkaTemplate<?, ?>} because all the recoverer needs
     * is something that can publish. Asking for {@code KafkaTemplate<String, Object>} assumed
     * every service that depends on vts-common serialises the same way — the scheduler, which
     * only produces and holds a {@code KafkaTemplate<String, String>}, then failed to start:
     * {@link ConditionalOnBean} matched the raw type, and the generic injection did not.
     */
    @Bean
    @ConditionalOnBean(KafkaTemplate.class)
    @ConditionalOnMissingBean(DefaultErrorHandler.class)
    public DefaultErrorHandler vtsKafkaErrorHandler(KafkaTemplate<?, ?> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) -> new TopicPartition(Topics.dlqFor(record.topic()), record.partition()));

        ExponentialBackOff backOff = new ExponentialBackOff(INITIAL_BACKOFF_MILLIS, BACKOFF_MULTIPLIER);
        backOff.setMaxInterval(MAX_BACKOFF_MILLIS);
        backOff.setMaxElapsedTime(MAX_ELAPSED_MILLIS);

        return new DefaultErrorHandler(recoverer, backOff);
    }
}
