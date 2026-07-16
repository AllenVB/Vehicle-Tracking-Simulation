package com.fleet.vts.simulator.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Simulator configuration. Fleet size and tick come only from the active
 * profile (dev: 100 / 5s, load: 1000 / 1s) — never hard-coded.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "vts.simulator")
public class SimulatorProperties {

    /** Number of simulated vehicles (their IMEIs are 000000000000001..N). */
    private int vehicleCount = 100;

    /** Interval between simulation ticks. */
    private Duration tick = Duration.ofSeconds(5);

    /** Base URL of the ingestion service. */
    private String ingestionBaseUrl = "http://localhost:8081";

    /** How long to wait for a TCP connection to ingestion before failing the tick. */
    private Duration ingestionConnectTimeout = Duration.ofSeconds(2);

    /**
     * How long to wait for ingestion's response. Bounded so a stalled ingestion cannot
     * pin the simulator's tick thread indefinitely — a dropped batch is recoverable,
     * a wedged simulator is not.
     */
    private Duration ingestionReadTimeout = Duration.ofSeconds(10);

    /** When true, vehicle routes follow real roads via OSRM (fallback: local loops). */
    private boolean roadRouting = true;

    /** OSRM routing service base URL (public demo server by default). */
    private String osrmBaseUrl = "https://router.project-osrm.org";
}
