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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Holds the simulated fleet and advances it every tick. Vehicles are ticked in
 * parallel on Java 21 virtual threads (one per vehicle), then their readings are
 * posted to ingestion as a single batch.
 *
 * <p>The fleet is spread across the 81 Turkish provinces weighted by population
 * ({@link TurkeyProvinces}); the operator console can pin any vehicle to a manual
 * position, which then flows through the real pipeline to the live map.
 */
@Component
public class FleetSimulator {

    private static final Logger log = LoggerFactory.getLogger(FleetSimulator.class);

    private final SimulatorProperties properties;
    private final IngestionClient ingestionClient;
    private final RoadRoutes roadRoutes;
    private final Counter sent;

    private List<VehicleState> fleet = List.of();
    private volatile Map<Long, VehicleState> byId = Map.of();
    private double tickSeconds;

    public FleetSimulator(SimulatorProperties properties, IngestionClient ingestionClient,
                          RoadRoutes roadRoutes, MeterRegistry registry) {
        this.properties = properties;
        this.ingestionClient = ingestionClient;
        this.roadRoutes = roadRoutes;
        this.sent = Counter.builder("simulator.telemetry.sent")
                .description("Telemetry readings posted to ingestion").register(registry);
    }

    @PostConstruct
    void initFleet() {
        this.tickSeconds = properties.getTick().toMillis() / 1000.0;

        // One slot per vehicle in the default 100-vehicle, population-weighted layout.
        List<TurkeyProvinces.Province> slots = new ArrayList<>();
        for (TurkeyProvinces.Province p : TurkeyProvinces.ALL) {
            for (int c = 0; c < p.vehicles(); c++) {
                slots.add(p);
            }
        }

        int target = properties.getVehicleCount();
        List<VehicleState> vehicles = new ArrayList<>(target);
        Map<Long, VehicleState> index = new LinkedHashMap<>(target * 2);
        boolean geofenceAssigned = false;
        Map<String, Route> routeCache = new HashMap<>();  // one road route per province, shared
        int[] roadProvinces = {0};

        for (int i = 1; i <= target; i++) {
            // Within the weighted layout use its slots; beyond it, round-robin the
            // provinces so a larger fleet (load profile) still spreads over Turkey.
            TurkeyProvinces.Province p = i <= slots.size()
                    ? slots.get(i - 1)
                    : TurkeyProvinces.ALL.get((i - 1) % TurkeyProvinces.ALL.size());

            BehaviorProfile profile = BehaviorProfile.forIndex(i);
            Route route;
            if (!geofenceAssigned && p.name().equals("İstanbul")) {
                // Keep one Istanbul vehicle looping inside the seeded restricted
                // geofence so geofence enter/exit events still fire.
                profile = BehaviorProfile.GEOFENCE;
                route = RouteFactory.restrictedZoneLoop();
                geofenceAssigned = true;
            } else {
                if (profile == BehaviorProfile.GEOFENCE) {
                    profile = BehaviorProfile.NORMAL; // geofence is only meaningful in Istanbul
                }
                TurkeyProvinces.Province prov = p;
                route = routeCache.computeIfAbsent(prov.name(), k -> {
                    Route road = roadRoutes.loopNear(prov.lat(), prov.lon());
                    if (road != null) {
                        roadProvinces[0]++;
                    }
                    return road != null ? road : RouteFactory.localLoop(prov.lat(), prov.lon());
                });
            }

            VehicleState v = new VehicleState(String.format("%015d", i), profile, route, i);
            v.setRegion(p.name());
            vehicles.add(v);
            index.put((long) i, v);
        }

        this.fleet = vehicles;
        this.byId = index;
        log.info("Fleet initialised: {} vehicles across {} provinces ({} on road routes), tick {}s",
                vehicles.size(), TurkeyProvinces.ALL.size(), roadProvinces[0], tickSeconds);
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

    // ── Operator-console control API ───────────────────────────────────────

    /** Current position of every simulated vehicle, for the operator console map. */
    public List<Map<String, Object>> positions() {
        List<Map<String, Object>> out = new ArrayList<>(byId.size());
        byId.forEach((id, v) -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", id);
            m.put("lat", v.lat());
            m.put("lon", v.lon());
            m.put("speedKmh", v.speedKmh());
            m.put("heading", v.heading());
            m.put("manual", v.isManual());
            m.put("region", v.region());
            out.add(m);
        });
        return out;
    }

    /** Pin a vehicle to a manual position (operator override). False if unknown id. */
    public boolean setManualPosition(long id, double lat, double lon) {
        VehicleState v = byId.get(id);
        if (v == null) {
            return false;
        }
        v.setManual(lat, lon);
        // Publish straight away instead of waiting for the next tick: otherwise the
        // move takes up to a full tick to even enter the pipeline, which is most of
        // the latency an operator perceives on the live map.
        v.tick(0);
        ingestionClient.sendBatch(List.of(toPayload(v)));
        sent.increment();
        return true;
    }

    /** Release a vehicle back to automatic simulation. False if unknown id. */
    public boolean releaseVehicle(long id) {
        VehicleState v = byId.get(id);
        if (v == null) {
            return false;
        }
        v.clearManual();
        return true;
    }
}
