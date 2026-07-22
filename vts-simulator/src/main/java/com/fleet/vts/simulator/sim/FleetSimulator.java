package com.fleet.vts.simulator.sim;

import com.fleet.vts.simulator.config.SimulatorProperties;
import com.fleet.vts.simulator.fuel.FuelLevelSource;
import com.fleet.vts.simulator.fuel.FuelStations;
import com.fleet.vts.simulator.ingestion.HttpTelemetryTransport;
import com.fleet.vts.simulator.ingestion.TelemetryPayload;
import com.fleet.vts.simulator.ingestion.TelemetryTransport;
import com.fleet.vts.simulator.ingestion.TeltonikaTelemetryTransport;
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
    /** Vehicle indices 101..105 are helicopters (they fly). */
    private static final int HELI_FIRST = 101;
    private static final int HELI_LAST = 105;
    /**
     * Centre of the seeded 'Tarihi Yarımada - Yasak Bölge' EXCLUSION polygon
     * (lon 28.955..28.985, lat 41.000..41.020) — the geofence vehicle laps around it.
     */
    private static final double GEOFENCE_ZONE_LAT = 41.010;
    private static final double GEOFENCE_ZONE_LON = 28.970;

    private final SimulatorProperties properties;
    private final TelemetryTransport transport;
    private final RoadRoutes roadRoutes;
    private final FuelStations fuelStations;
    private final FuelLevelSource fuelLevelSource;
    private final Counter sent;
    private final Random rnd = new Random(42);

    private List<VehicleState> fleet = List.of();
    private volatile Map<Long, VehicleState> byId = Map.of();
    private double tickSeconds;
    private ScheduledExecutorService assigner;

    public FleetSimulator(SimulatorProperties properties,
                          HttpTelemetryTransport httpTransport,
                          TeltonikaTelemetryTransport deviceTransport,
                          RoadRoutes roadRoutes, FuelStations fuelStations,
                          FuelLevelSource fuelLevelSource, MeterRegistry registry) {
        this.properties = properties;
        // Chosen here rather than by a conditional bean so the alternative is visible: both
        // transports are always constructed, and switching is a property, not a redeploy.
        this.transport = properties.getTransport() == SimulatorProperties.Transport.TELTONIKA
                ? deviceTransport : httpTransport;
        this.roadRoutes = roadRoutes;
        this.fuelStations = fuelStations;
        this.fuelLevelSource = fuelLevelSource;
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

            String imei = String.format("%015d", i);
            VehicleState v = null;

            if (i >= HELI_FIRST && i <= HELI_LAST) {
                // Helicopters (101..105): flying journeys, high speed, exempt from road rules.
                v = VehicleState.helicopter(imei, p.lat(), p.lon(), i);
            } else if (!geofenceAssigned && p.name().equals("İstanbul")) {
                // One Istanbul vehicle circles the seeded restricted geofence, so enter/exit
                // events keep firing. It takes no destination.
                //
                // The loop follows real roads through the zone. It used to be a synthetic
                // circle of radius ~0.006 deg, which sat wholly *inside* the polygon — so it
                // fired ENTER once and then never crossed the boundary again. A road loop
                // around the zone crosses it every lap, which is what the demo wanted.
                Route zoneLoop = roadRoutes.loopNear(GEOFENCE_ZONE_LAT, GEOFENCE_ZONE_LON);
                if (zoneLoop != null) {
                    v = new VehicleState(imei, BehaviorProfile.GEOFENCE, zoneLoop, i);
                    geofenceAssigned = true;
                } else {
                    // Routing is down. Fall through to an ordinary journey vehicle rather than
                    // circle a made-up loop: no land vehicle moves on invented geometry.
                    log.warn("No road loop for the geofence zone; vehicle {} takes journeys instead", i);
                }
            }
            if (v == null) {
                BehaviorProfile profile = BehaviorProfile.forIndex(i);
                if (profile == BehaviorProfile.GEOFENCE) {
                    profile = BehaviorProfile.NORMAL; // geofence is only meaningful in Istanbul
                }
                v = new VehicleState(imei, profile, p.lat(), p.lon(), i);
            }
            // Only land vehicles burn fuel; the constructor leaves helicopters at zero drain
            // and this must not override that.
            if (v.usesFuel()) {
                v.setFuelDrainPctPerMinute(properties.getFuelDrainPctPerMinute());
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

        log.info("Fleet initialised: {} vehicles across {} provinces, tick {}s, transport {}",
                vehicles.size(), TurkeyProvinces.ALL.size(), tickSeconds, transport.name());
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
            divertLowFuelVehicles();
        } catch (Exception e) {
            log.warn("Journey assignment cycle failed: {}", e.getMessage());
        }
    }

    /**
     * Send vehicles whose tank has fallen to the warning level to the nearest station.
     *
     * <p>Runs on the assigner rather than the tick, for the same reason journeys do: reaching a
     * station needs a road route, and an OSRM call on the 1-second tick would stall the whole
     * fleet's batch.
     *
     * <p>Only vehicles already driving are diverted here. One that is parked keeps its low tank
     * until its dwell ends, and {@link #assignJourney} then sends it to a pump instead of a
     * province — there is nothing to gain from waking a parked vehicle early, and diverting it
     * would cut short the stop its trip record depends on.
     */
    private void divertLowFuelVehicles() {
        if (fuelStations.isEmpty()) {
            return;   // reference data not loaded yet; vehicles keep driving
        }
        List<VehicleState> lowOnFuel = fleet.stream()
                .filter(v -> !v.isLoopVehicle() && v.usesFuel())
                .filter(v -> !v.isSeekingFuel() && !v.isParked() && v.journey() != null)
                .filter(v -> v.fuelPct() <= properties.getLowFuelThresholdPct())
                .limit(ASSIGN_BATCH)
                .toList();
        for (VehicleState v : lowOnFuel) {
            dispatchToFuelStation(v);
        }
    }

    /** A vehicle asking for work goes to a pump when its tank is low, otherwise to a province. */
    private void assignJourney(VehicleState v, double startProgress) {
        if (v.usesFuel() && v.fuelPct() <= properties.getLowFuelThresholdPct()
                && !fuelStations.isEmpty() && dispatchToFuelStation(v)) {
            return;
        }
        startFreshJourney(v, v.lat(), v.lon(), startProgress);
    }

    /**
     * Route {@code v} to the fuel station nearest to it. False when no road route reaches one,
     * in which case the caller leaves the vehicle as it was — the same refusal to invent
     * geometry that applies to every other journey.
     */
    private boolean dispatchToFuelStation(VehicleState v) {
        FuelStations.Station station = fuelStations.nearest(v.lat(), v.lon());
        if (station == null) {
            return false;
        }
        Route route = routeFor(v, v.lat(), v.lon(), station.lat(), station.lon());
        if (route == null) {
            return false;
        }
        v.startRefuelJourney(
                new Journey(station.name(), station.lat(), station.lon(), route),
                properties.getRefuelDwell().toSeconds());
        return true;
    }

    /**
     * Send the vehicle from {@code (fromLat, fromLon)} to a fresh destination drawn from the
     * provinces near that point, and start it driving.
     *
     * <p>The destination is chosen from where the vehicle <em>is</em>, which is what makes an
     * operator's move meaningful: keeping the old destination sent a vehicle teleported from
     * Hatay to Ankara straight back south-east, so the move undid itself.
     *
     * <p>Returns false when a land vehicle has no road route from here — the caller must then
     * leave it be rather than invent one. A straight line would let it "still travel, arrive
     * and close trips" while driving through mountains and lakes, which is exactly what a land
     * vehicle cannot do. The vehicle keeps {@code needsJourney()}, so the assigner retries in
     * a few seconds; a stationary vehicle is a visible, honest symptom of routing being down,
     * whereas one crossing a lake is not.
     */
    private boolean startFreshJourney(VehicleState v, double fromLat, double fromLon,
                                      double startProgress) {
        TurkeyProvinces.Province dest = TurkeyProvinces.nearbyDestination(
                fromLat, fromLon, DESTINATION_CANDIDATES, rnd);

        Route route = routeFor(v, fromLat, fromLon, dest.lat(), dest.lon());
        if (route == null) {
            return false;
        }
        v.startJourney(new Journey(dest.name(), dest.lat(), dest.lon(), route), startProgress);
        v.setRegion(dest.name());
        return true;
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
                    // A real fuel source, once one is registered, is the authority for the
                    // vehicles it knows: applied after the tick so it overrides the simulated
                    // drain rather than racing it. Vehicles it has no reading for keep theirs.
                    fuelLevelSource.readFuelPct(v.imei()).ifPresent(v::overrideFuelPct);
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
        transport.send(readings);
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
            m.put("region", v.region());
            m.put("destination", v.destination());
            m.put("destLat", v.destLat());
            m.put("destLon", v.destLon());
            m.put("remainingKm", Math.round(v.remainingKm() * 10) / 10.0);
            m.put("etaMinutes", v.etaMinutes());
            m.put("parked", v.isParked());
            m.put("flying", v.isFlying());
            out.add(m);
        });
        return out;
    }

    /** The route still ahead for a vehicle, as [[lat, lon], ...] (for the UI to draw). */
    public List<double[]> routeGeometry(long id) {
        VehicleState v = byId.get(id);
        if (v == null) {
            return List.of();
        }
        List<double[]> out = new ArrayList<>();
        v.remainingRoute().forEach(p -> out.add(new double[]{p.lat(), p.lon()}));
        return out;
    }

    /**
     * Move a vehicle to an operator-chosen point and let it carry on driving.
     *
     * <p>A land vehicle is only moved if the click is on a road; an off-road click is
     * refused and the vehicle stays where it is. It used to be snapped to the nearest road
     * instead, which quietly put the vehicle somewhere the operator had not chosen —
     * a refusal the operator can see beats a silent correction they cannot.
     * Helicopters are placed exactly where clicked; they fly, so every point is reachable.
     *
     * <p>The move is a teleport, not a pin: the vehicle lands at the point and drives on at its
     * own cruise speed. It used to be frozen at 0 km/h until an operator released it, which made
     * every move look like a breakdown and stalled the vehicle's trip.
     *
     * <p>It keeps the destination it already had, so a move relocates the vehicle without
     * cancelling its errand — it finishes the run it was on, then parks, is scored, and takes a
     * new route from there. Note this can send it back the way it came: a vehicle carried from
     * Hatay to Ankara still owes a trip to Kayseri and will head south-east to make it. That is
     * the deliberate reading of "let them finish their own route first"; the alternative —
     * picking a fresh destination near the drop point — silently cancels whatever the vehicle
     * was doing, which is a bigger surprise than driving back.
     *
     * <p>Returns the outcome — {@code moved} says whether it happened, {@code reason} why
     * not, {@code destination} where it is headed — or {@code null} if the id is unknown.
     */
    public Map<String, Object> moveVehicle(long id, double lat, double lon) {
        VehicleState v = byId.get(id);
        if (v == null) {
            return null;
        }
        Map<String, Object> result = new HashMap<>();
        result.put("found", true);
        result.put("flying", v.isFlying());

        if (v.isLoopVehicle()) {
            // The geofence vehicle exists to lap its zone, and its position is recomputed
            // from that loop on every tick — so relocating it would be undone before the
            // next reading and the move would be a lie the API told the operator.
            return refuse(result, "LOOP_VEHICLE", null);
        }

        double placeLat = lat;
        double placeLon = lon;

        if (!v.isFlying()) {
            RoadRoutes.NearestRoad nearest = roadRoutes.nearestRoad(lat, lon);
            if (nearest == null) {
                return refuse(result, "ROUTING_UNAVAILABLE", null);
            }
            if (nearest.distanceMeters() > properties.getRoadClickToleranceMeters()) {
                return refuse(result, "OFF_ROAD", nearest.distanceMeters());
            }
            // Within tolerance: settle onto the road itself, so the vehicle sits on the
            // lane rather than a few metres beside it.
            placeLat = nearest.lat();
            placeLon = nearest.lon();
        }

        // Resume the errand it was already on, re-routed from where it now stands. A vehicle
        // that was parked or waiting has no errand to resume, so it gets a fresh one instead.
        Journey current = v.journey();
        boolean resumed = current != null && !v.isParked()
                && resumeJourneyFrom(v, placeLat, placeLon, current);
        if (!resumed && !startFreshJourney(v, placeLat, placeLon, 0.0)) {
            return refuse(result, "NO_ROUTE_FROM_HERE", null);
        }

        // Publish straight away instead of waiting for the next tick: otherwise the move
        // takes up to a full tick to enter the pipeline — most of the perceived latency.
        v.tick(0);
        transport.send(List.of(toPayload(v)));
        sent.increment();

        result.put("moved", true);
        result.put("lat", v.lat());
        result.put("lon", v.lon());
        result.put("speedKmh", v.speedKmh());
        result.put("destination", v.destination());
        return result;
    }

    private static Map<String, Object> refuse(Map<String, Object> result, String reason,
                                              Double offRoadMeters) {
        result.put("moved", false);
        result.put("reason", reason);
        if (offRoadMeters != null) {
            result.put("offRoadMeters", Math.round(offRoadMeters));
        }
        return result;
    }

    /**
     * Put the vehicle back on the errand it was already running, routed from its new position
     * to the destination it already had. False when no road route reaches that destination
     * from here, in which case the caller falls back to a fresh journey rather than stranding
     * it — an unreachable old destination is no reason to leave a vehicle standing.
     */
    private boolean resumeJourneyFrom(VehicleState v, double fromLat, double fromLon,
                                      Journey current) {
        Route route = routeFor(v, fromLat, fromLon, current.destLat(), current.destLon());
        if (route == null) {
            return false;
        }
        v.startJourney(new Journey(current.destination(), current.destLat(), current.destLon(),
                route), 0.0);
        return true;
    }

    /** A helicopter's route to a destination is the straight line; a land vehicle's is OSRM's. */
    private Route routeFor(VehicleState v, double fromLat, double fromLon,
                           double toLat, double toLon) {
        if (v.isFlying()) {
            return new Route(List.of(new GeoPoint(fromLat, fromLon), new GeoPoint(toLat, toLon)),
                    false);
        }
        return roadRoutes.routeBetween(fromLat, fromLon, toLat, toLon);
    }

    /** All province names (for the operator's "create route" destination picker). */
    public List<String> provinceNames() {
        return TurkeyProvinces.ALL.stream()
                .map(TurkeyProvinces.Province::name)
                .sorted()
                .toList();
    }

    /**
     * Operator-chosen destination: dispatch the vehicle on a fresh journey to the named
     * province from its current position (road route for vehicles, straight line for
     * helicopters). Releases any manual pin so it starts moving. False if id/province unknown.
     */
    public boolean dispatchTo(long id, String provinceName) {
        VehicleState v = byId.get(id);
        if (v == null) {
            return false;
        }
        TurkeyProvinces.Province dest = TurkeyProvinces.byName(provinceName);
        if (dest == null) {
            return false;
        }
        Route route = routeFor(v, v.lat(), v.lon(), dest.lat(), dest.lon());
        if (route == null) {
            // A land vehicle with no road route is refused, not sent across country on a
            // straight line — the operator gets an error instead of a car crossing a lake.
            return false;
        }
        v.startJourney(new Journey(dest.name(), dest.lat(), dest.lon(), route), 0.0);
        v.setRegion(dest.name());
        return true;
    }
}
