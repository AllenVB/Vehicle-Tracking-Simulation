package com.fleet.vts.simulator.sim;

import com.fleet.vts.simulator.config.SimulatorProperties;
import com.fleet.vts.simulator.ingestion.IngestionClient;
import com.fleet.vts.simulator.ingestion.TelemetryPayload;
import com.fleet.vts.simulator.model.BehaviorProfile;
import com.fleet.vts.simulator.model.GeoPoint;
import com.fleet.vts.simulator.model.Journey;
import com.fleet.vts.simulator.model.Route;
import com.fleet.vts.simulator.model.VehicleState;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Holds the simulated fleet and advances it every tick, on Java 21 virtual threads.
 *
 * <p>Vehicles run real <b>journeys</b>: a named destination in a nearby province, a real
 * OSRM road route to it, and a park on arrival long enough to close the trip. That is
 * what makes trips, stop events, idling and driver scores exist at all — a fleet that
 * merely circles forever never arrives anywhere, so those tables stay empty.
 *
 * <p>Routing is deliberately kept OFF the tick path: an OSRM call takes seconds and
 * would stall the 1-second batch. Vehicles that need a destination raise a flag, and a
 * background assigner picks them up.
 */
@Component
public class FleetSimulator {

    private static final Logger log = LoggerFactory.getLogger(FleetSimulator.class);

    /** Destinations are drawn from this many nearest provinces (intercity, but not absurd). */
    private static final int DESTINATION_CANDIDATES = 8;
    /** How many routes we ask OSRM for per assigner cycle (be a polite client). */
    private static final int ASSIGN_BATCH = 6;

    private final SimulatorProperties properties;
    private final IngestionClient ingestionClient;
    private final RoadRoutes roadRoutes;
    private final Counter sent;
    private final Random rnd = new Random(42);

    private List<VehicleState> fleet = List.of();
    private volatile Map<Long, VehicleState> byId = Map.of();
    private double tickSeconds;
    private ScheduledExecutorService assigner;

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

        for (int i = 1; i <= target; i++) {
            TurkeyProvinces.Province p = i <= slots.size()
                    ? slots.get(i - 1)
                    : TurkeyProvinces.ALL.get((i - 1) % TurkeyProvinces.ALL.size());

            BehaviorProfile profile = BehaviorProfile.forIndex(i);
            VehicleState v;
            if (!geofenceAssigned && p.name().equals("İstanbul")) {
                // One Istanbul vehicle circles inside the seeded restricted geofence, so
                // geofence enter/exit events keep firing. It takes no destination.
                v = new VehicleState(String.format("%015d", i), BehaviorProfile.GEOFENCE,
                        RouteFactory.restrictedZoneLoop(), i);
                geofenceAssigned = true;
            } else {
                if (profile == BehaviorProfile.GEOFENCE) {
                    profile = BehaviorProfile.NORMAL; // geofence is only meaningful in Istanbul
                }
                v = new VehicleState(String.format("%015d", i), profile, p.lat(), p.lon(), i);
            }
            v.setRegion(p.name());
            vehicles.add(v);
            index.put((long) i, v);
        }

        this.fleet = vehicles;
        this.byId = index;

        assignInitialJourneys(vehicles);

        this.assigner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "journey-assigner");
            t.setDaemon(true);
            return t;
        });
        this.assigner.scheduleWithFixedDelay(this::assignPending, 3, 3, TimeUnit.SECONDS);

        log.info("Fleet initialised: {} vehicles across {} provinces, tick {}s",
                vehicles.size(), TurkeyProvinces.ALL.size(), tickSeconds);
    }

    @PreDestroy
    void stopAssigner() {
        if (assigner != null) {
            assigner.shutdownNow();
        }
    }

    /**
     * Give every vehicle its first journey, in parallel but with bounded concurrency so
     * we do not hammer OSRM. Each starts at a RANDOM point along its route: otherwise the
     * whole fleet would depart together and the first arrivals (and trips) would only
     * appear hours later.
     */
    private void assignInitialJourneys(List<VehicleState> vehicles) {
        long t0 = System.currentTimeMillis();
        List<VehicleState> pending = vehicles.stream().filter(v -> !v.isLoopVehicle()).toList();
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<Future<?>> futures = new ArrayList<>(pending.size());
            for (VehicleState v : pending) {
                futures.add(pool.submit(() -> assignJourney(v, rnd.nextDouble() * 0.95)));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception e) {
                    log.warn("Initial journey failed: {}", e.getMessage());
                }
            }
        }
        long routed = pending.stream().filter(v -> v.journey() != null).count();
        log.info("Initial journeys assigned: {}/{} in {} ms",
                routed, pending.size(), System.currentTimeMillis() - t0);
    }

    /** Vehicles that finished their park ask for a new destination; serve a few per cycle. */
    private void assignPending() {
        try {
            List<VehicleState> pending = fleet.stream()
                    .filter(v -> !v.isLoopVehicle() && v.needsJourney())
                    .limit(ASSIGN_BATCH)
                    .toList();
            for (VehicleState v : pending) {
                assignJourney(v, 0.0);
            }
        } catch (Exception e) {
            log.warn("Journey assignment cycle failed: {}", e.getMessage());
        }
    }

    private void assignJourney(VehicleState v, double startProgress) {
        TurkeyProvinces.Province dest = TurkeyProvinces.nearbyDestination(
                v.lat(), v.lon(), DESTINATION_CANDIDATES, rnd);

        Route route = roadRoutes.routeBetween(v.lat(), v.lon(), dest.lat(), dest.lon());
        if (route == null) {
            // OSRM unavailable: fall back to a straight line so the vehicle still travels,
            // still arrives, and still closes trips — just not on real roads.
            route = new Route(List.of(new GeoPoint(v.lat(), v.lon()),
                    new GeoPoint(dest.lat(), dest.lon())), false);
        }
        v.startJourney(new Journey(dest.name(), dest.lat(), dest.lon(), route), startProgress);
        v.setRegion(dest.name());
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

    // ── Operator-console / dispatch API ────────────────────────────────────

    /** Current position and journey of every simulated vehicle. */
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
            m.put("destination", v.destination());
            m.put("remainingKm", Math.round(v.remainingKm() * 10) / 10.0);
            m.put("etaMinutes", v.etaMinutes());
            m.put("parked", v.isParked());
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
