package com.fleet.vts.ingestion;

import com.fleet.vts.ingestion.port.in.TelemetryInboundPort;
import com.fleet.vts.ingestion.port.out.TelemetryPublisherPort;
import com.fleet.vts.ingestion.port.out.VehicleLookupPort;
import com.fleet.vts.testsupport.VtsInfra;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** Boots ingestion against real Postgres, Kafka and Redis; every port must have an adapter. */
@SpringBootTest(properties = {
        // The device listener binds a real socket. On the configured port it collides with a
        // running stack — the test would then fail for a reason that has nothing to do with
        // the code, which is the least useful kind of red build.
        "vts.ingestion.teltonika.port=0",
        "management.tracing.sampling.probability=0.0"
})
class IngestionContextIT {

    @DynamicPropertySource
    static void infra(DynamicPropertyRegistry registry) {
        VtsInfra.all(registry);
    }

    @Autowired
    private TelemetryInboundPort inbound;

    @Autowired
    private TelemetryPublisherPort publisher;

    @Autowired
    private VehicleLookupPort lookup;

    @Test
    void contextLoadsWithEveryPortBound() {
        assertThat(inbound).isNotNull();
        assertThat(publisher).isNotNull();
        assertThat(lookup).isNotNull();
    }
}
