package com.fleet.vts.gateway;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Verifies that every JPA entity matches the Flyway-managed schema. Boots the
 * application context against a running TimescaleDB+PostGIS instance: Flyway
 * runs V1..V15, then Hibernate validates all mappings (ddl-auto=validate). Any
 * wrong column/type fails context startup and thus this test.
 *
 * The database is expected at {@code VTS_SCHEMA_JDBC_URL} (default
 * localhost:55432); the surrounding script starts/stops that container. Security
 * and Redis auto-configuration are excluded to keep the check on persistence.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.flyway.placeholders.telemetry_chunk_interval=1 day",
        "spring.flyway.placeholders.retention_days=30",
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration,"
                + "org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
})
// Integration test: needs a running TimescaleDB (see VTS_SCHEMA_JDBC_URL).
// Run with: mvn -Dvts.itest=true test  (after starting the DB container).
@EnabledIfSystemProperty(named = "vts.itest", matches = "true")
class SchemaValidationTest {

    private static final String JDBC_URL = System.getenv()
            .getOrDefault("VTS_SCHEMA_JDBC_URL", "jdbc:postgresql://localhost:55432/vts");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> JDBC_URL);
        registry.add("spring.datasource.username", () -> "vts");
        registry.add("spring.datasource.password", () -> "vts");
    }

    @Test
    void entitiesMatchSchema() {
        // Reaching here means Flyway migrated and Hibernate validated all
        // entities against the live schema without error.
    }
}
