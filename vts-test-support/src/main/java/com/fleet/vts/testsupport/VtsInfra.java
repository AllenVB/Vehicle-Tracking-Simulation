package com.fleet.vts.testsupport;

import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Points a Spring context at the shared containers. Every service reads the same three
 * property groups, so the wiring is written once here instead of seven times.
 *
 * <p>Each method starts only the container it needs: a service without Redis never pays
 * for a Redis container.
 */
public final class VtsInfra {

    private VtsInfra() {
    }

    public static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", VtsContainers::postgresJdbcUrl);
        registry.add("spring.datasource.username", VtsContainers::postgresUsername);
        registry.add("spring.datasource.password", VtsContainers::postgresPassword);
        // The gateway owns the schema and the container already ran the migrations; a second
        // Flyway run inside the context would be a no-op, but an explicit one is clearer.
        registry.add("spring.flyway.enabled", () -> "false");
    }

    public static void kafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", VtsContainers::kafkaBootstrapServers);
    }

    public static void redis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", VtsContainers::redisHost);
        registry.add("spring.data.redis.port", VtsContainers::redisPort);
    }

    /** Everything at once, for services that touch all three. */
    public static void all(DynamicPropertyRegistry registry) {
        postgres(registry);
        kafka(registry);
        redis(registry);
    }
}
