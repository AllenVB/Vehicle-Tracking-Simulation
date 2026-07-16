package com.fleet.vts.common.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.Map;

/**
 * Builds the typed listener factories every service consumes events with. Each service
 * used to carry its own near-identical copy of this wiring; they now share one, so a
 * consumer cannot silently miss the deserialization or error-handling setup.
 *
 * <p>The value deserializer is wrapped in an {@link ErrorHandlingDeserializer}. Without it
 * a malformed payload throws inside the consumer's poll loop, where no error handler can
 * see it, and the container retries the same offset forever — the poison-pill stall. Wrapped,
 * the failure is handed to the {@link CommonErrorHandler} as a record, which can retry it and
 * ultimately dead-letter it.
 */
public final class VtsKafkaConsumers {

    /** Event records live here; the JSON deserializer will not instantiate types outside it. */
    private static final String TRUSTED_PACKAGE = "com.fleet.vts.common.event";

    private VtsKafkaConsumers() {
    }

    /**
     * A consumer factory for {@code type}, keyed by String, reading JSON without relying on
     * type headers (producers publish with {@code addTypeInfo=false}).
     */
    public static <T> ConsumerFactory<String, T> consumerFactory(KafkaProperties properties,
                                                                 ObjectMapper objectMapper,
                                                                 Class<T> type) {
        Map<String, Object> config = properties.buildConsumerProperties(null);
        JsonDeserializer<T> delegate = new JsonDeserializer<>(type, objectMapper, false);
        delegate.setUseTypeHeaders(false);
        delegate.addTrustedPackages(TRUSTED_PACKAGE);
        return new DefaultKafkaConsumerFactory<>(
                config, new StringDeserializer(), new ErrorHandlingDeserializer<>(delegate));
    }

    /** A record-at-a-time listener factory for {@code type}, with dead-lettering wired in. */
    public static <T> ConcurrentKafkaListenerContainerFactory<String, T> listenerFactory(
            KafkaProperties properties, ObjectMapper objectMapper, Class<T> type,
            CommonErrorHandler errorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, T> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory(properties, objectMapper, type));
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }
}
