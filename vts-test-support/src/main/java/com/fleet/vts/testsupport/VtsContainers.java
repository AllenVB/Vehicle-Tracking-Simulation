package com.fleet.vts.testsupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The infrastructure every context test shares: one migrated TimescaleDB, one Kafka broker,
 * one Redis. Started lazily on first use and kept for the life of the JVM.
 *
 * <p>Images are pinned to exactly what {@code docker-compose.yml} runs. A context test that
 * passes against a different Postgres would be answering an easier question than production
 * asks — PostGIS and TimescaleDB are load-bearing here, not incidental.
 *
 * <p>Containers are marked reusable, so a whole multi-module build shares a single set when
 * {@code testcontainers.reuse.enable=true} is set. Reuse is an optimisation only: without it
 * each module simply starts its own.
 */
public final class VtsContainers {

    private static final Logger log = LoggerFactory.getLogger(VtsContainers.class);

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("timescale/timescaledb-ha:pg16")
            .asCompatibleSubstituteFor("postgres");
    private static final DockerImageName KAFKA_IMAGE = DockerImageName.parse("apache/kafka:3.8.1");
    private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7-alpine");

    private VtsContainers() {
    }

    // Holder classes: each container starts on first touch, never before.

    private static final class Postgres {
        @SuppressWarnings("resource") // shared for the JVM lifetime; Ryuk reaps it
        static final PostgreSQLContainer<?> INSTANCE = new PostgreSQLContainer<>(POSTGRES_IMAGE)
                .withDatabaseName("vts")
                .withUsername("vts")
                .withPassword("vts")
                .withReuse(true);

        static {
            INSTANCE.start();
            long t0 = System.nanoTime();
            int applied = VtsMigrations.migrate(INSTANCE.getJdbcUrl(),
                    INSTANCE.getUsername(), INSTANCE.getPassword());
            log.info("Flyway applied {} migration(s) in {} ms",
                    applied, (System.nanoTime() - t0) / 1_000_000);
        }
    }

    private static final class Kafka {
        @SuppressWarnings("resource")
        static final KafkaContainer INSTANCE = new KafkaContainer(KAFKA_IMAGE).withReuse(true);

        static {
            INSTANCE.start();
        }
    }

    private static final class Redis {
        @SuppressWarnings("resource")
        static final GenericContainer<?> INSTANCE =
                new GenericContainer<>(REDIS_IMAGE).withExposedPorts(6379).withReuse(true);

        static {
            INSTANCE.start();
        }
    }

    /** JDBC URL of the shared TimescaleDB, with the full schema already migrated. */
    public static String postgresJdbcUrl() {
        return Postgres.INSTANCE.getJdbcUrl();
    }

    public static String postgresUsername() {
        return Postgres.INSTANCE.getUsername();
    }

    public static String postgresPassword() {
        return Postgres.INSTANCE.getPassword();
    }

    public static String kafkaBootstrapServers() {
        return Kafka.INSTANCE.getBootstrapServers();
    }

    public static String redisHost() {
        return Redis.INSTANCE.getHost();
    }

    public static int redisPort() {
        return Redis.INSTANCE.getMappedPort(6379);
    }

    /**
     * A throwaway, never-reused, never-migrated Postgres. The migration test needs a database
     * that has genuinely never seen these migrations; a reused one would report "0 applied"
     * and pass while proving nothing.
     */
    @SuppressWarnings("resource") // the caller closes it
    public static PostgreSQLContainer<?> freshPostgres() {
        return new PostgreSQLContainer<>(POSTGRES_IMAGE)
                .withDatabaseName("vts")
                .withUsername("vts")
                .withPassword("vts");
    }
}
