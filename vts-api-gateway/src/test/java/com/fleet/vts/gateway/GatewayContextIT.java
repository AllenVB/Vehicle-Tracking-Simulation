package com.fleet.vts.gateway;

import com.fleet.vts.testsupport.VtsInfra;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Boots the gateway against real Postgres, Kafka and Redis.
 *
 * <p>This is also the schema check: {@code ddl-auto=validate} runs Hibernate over every JPA
 * entity against the Flyway-built schema, so a column that a migration renamed and an entity
 * did not fails context startup. It replaces the earlier {@code SchemaValidationTest}, which
 * asked the same question but only ran when someone had started a database by hand and
 * passed {@code -Dvts.itest=true} — which nobody did.
 *
 * <p>Security stays on, unlike in that test: JWT filters, the STOMP channel interceptor and
 * the resource-server wiring are exactly the kind of bean graph a context test is for. The
 * unauthenticated 401 below proves the filter chain is actually in place.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "management.tracing.sampling.probability=0.0"
})
@AutoConfigureMockMvc
class GatewayContextIT {

    @DynamicPropertySource
    static void infra(DynamicPropertyRegistry registry) {
        VtsInfra.all(registry);
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void contextLoadsAndEntitiesMatchTheFlywaySchema() throws Exception {
        // Reaching here means Hibernate validated every mapping against the live schema.
        mockMvc.perform(get("/api/v1/vehicles")).andExpect(status().isUnauthorized());
    }
}
