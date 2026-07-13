package com.fleet.vts.analytics.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.support.serializer.JsonSerde;

/** Builds header-free JSON serdes bound to a fixed target type. */
public final class Serdes {

    private Serdes() {
    }

    public static <T> JsonSerde<T> json(Class<T> type, ObjectMapper mapper) {
        JsonSerde<T> serde = new JsonSerde<>(type, mapper);
        serde.deserializer().setUseTypeHeaders(false);
        serde.deserializer().addTrustedPackages("*");
        serde.serializer().setAddTypeInfo(false);
        return serde;
    }
}
