package com.fleet.vts.simulator.sim;

import com.fleet.vts.simulator.config.SimulatorProperties;
import com.fleet.vts.simulator.ingestion.IngestionClient;
import com.fleet.vts.simulator.ingestion.TelemetryPayload;
import com.fleet.vts.simulator.model.BehaviorProfile;
import com.fleet.vts.simulator.model.Route;
import com.fleet.vts.simulator.model.VehicleState;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Holds the simulated fleet and advances it every tick. Vehicles are ticked in
 * parallel on Java 21 virtual threads (one per vehicle), then their readings are
 * posted to ingestion as a single batch.
 */
@Component
public class FleetSimulator {

    private static final Logger log = LoggerFactory.getLogger(FleetSimulator.class);

    private final SimulatorProperties properties;
    private final IngestionClient ingestionClient;
    private final Counter sent;

    private List<VehicleState> fleet = List.of();
    private double tickSeconds;

    public FleetSimulator(SimulatorProperties properties, IngestionClient ingestionClient,
                          MeterRegistry registry) {
        this.properties = properties;
        this.ingestionClient = ingestionClient;
        this.sent = Counter.builder("simulator.telemetry.sent")
                .description("Telemetry readings posted to ingestion").register(registry);
    }

    @PostConstruct
    void initFleet() {
        List<Route> routes = RouteFactory.build();
        this.tickSeconds = properties.getTick().toMillis() / 1000.0;
        List<VehicleState> vehicles = new ArrayList<>(properties.getVehicleCount());
        for (int i = 1; i <= properties.getVehicleCount(); i++) {
            BehaviorProfile profile = BehaviorProfile.forIndex(i);
            // Restricted-zone loop (index 0) for geofence anomalies; scattered loops otherwise.
            Route route = profile == BehaviorProfile.GEOFENCE
                    ? routes.get(0)
                    : routes.get(1 + (i - 1) % (RouteFactory.ROUTE_COUNT - 1));
            String imei = String.format("%015d", i);
            vehicles.add(new VehicleState(imei, profile, route, i));
        }
        this.fleet = vehicles;
        log.info("Fleet initialised: {} vehicles, tick {}s", vehicles.size(), tickSeconds);
    }

    /** One simulation cycle: advance every vehicle in parallel, then batch-send. */
    public void tick() {
        if (fleet.isEmpty()) {
            return;
        }
        List<TelemetryPayload> readings = new ArrayList<>(fleet.size());
        try (ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<TelemetryPayload>> futures = new ArrayList<>(fleet.size());
            for (VehicleState v : fleet) {
                futures.add(vt.submit(() -> {
                    v.tick(tickSeconds);
                    return toPayload(v);
                }));
            }
            for (Future<TelemetryPayload> f : futures) {
                try {
                    readings.add(f.get());
                } catch (Exception e) {
                    log.warn("Vehicle tick failed: {}", e.getMessage());
                }
            }
        }
        ingestionClient.sendBatch(readings);
        sent.increment(readings.size());
    }

    private TelemetryPayload toPayload(VehicleState v) {
        return new TelemetryPayload(v.imei(), Instant.now(), v.lat(), v.lon(),
                v.speedKmh(), v.heading(), v.battery(), v.fuelPct(),
                v.engineOn(), v.ignition(), v.odometerKm(), null);
    }
}
