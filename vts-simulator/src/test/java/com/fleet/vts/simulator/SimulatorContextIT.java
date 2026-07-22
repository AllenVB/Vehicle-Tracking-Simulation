package com.fleet.vts.simulator;

import com.fleet.vts.simulator.sim.FleetSimulator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the simulator. It is the one service with no infrastructure at all — deliberately no
 * database, and its only outbound dependency is ingestion over HTTP — so the context test
 * needs no containers.
 *
 * <p>Road routing is off and the fleet is small: with routing on, {@code @PostConstruct}
 * asks OSRM for a route per vehicle, and a test has no OSRM. Fleet construction, province
 * distribution and the helicopter split are still exercised.
 */
@SpringBootTest(properties = {
        "vts.simulator.vehicle-count=6",
        "vts.simulator.road-routing=false",
        "management.tracing.sampling.probability=0.0"
})
class SimulatorContextIT {

    @Autowired
    private FleetSimulator simulator;

    @Test
    void contextLoadsAndFleetIsBuilt() {
        assertThat(simulator).isNotNull();
        assertThat(simulator.positions()).hasSize(6);
    }
}
