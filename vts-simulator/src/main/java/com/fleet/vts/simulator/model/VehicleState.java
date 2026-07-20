package com.fleet.vts.simulator.model;

import java.util.List;
import java.util.Random;

/**
 * Mutable per-vehicle simulation state.
 *
 * <p>Movement modes:
 * <ul>
 *   <li><b>Journey</b> (the norm): drives a real OSRM road route toward a named
 *       destination, parks on arrival long enough to close the trip, then asks for a
 *       new journey. This is what fills {@code trip}/{@code trip_point} and gives driver
 *       scoring something to score.</li>
 *   <li><b>Flying journey</b> (helicopters, index 101..105): the same journey lifecycle
 *       but with a straight-line route (crow flies) and a much higher cruise speed. A
 *       helicopter can be placed anywhere — sea, buildings — and is exempt from the
 *       road-based rules (enforced by vehicle type in the backend).</li>
 *   <li><b>Loop</b>: a closed polyline circled forever. Used only for the geofence
 *       vehicle, which must stay inside the restricted zone.</li>
 * </ul>
 *
 * <p>Not thread-safe by design: each vehicle is ticked by exactly one virtual thread
 * per cycle. Fields written from the HTTP/journey threads are {@code volatile}.
 */
public class VehicleState {

    /** Park long enough on arrival that the 5-minute trip-stop window elapses. */
    private static final int DWELL_MIN_SECONDS = 330;   // 5.5 dk
    private static final int DWELL_MAX_SECONDS = 720;   // 12 dk

    /** Tank level after a refuel stop. */
    private static final double FULL_TANK_PCT = 100.0;
    /** Default tank drain while driving; overridden from configuration at fleet build. */
    private static final double DEFAULT_FUEL_DRAIN_PCT_PER_MINUTE = 2.0;

    private final String imei;
    private final BehaviorProfile profile;
    private final Random rnd;
    private final boolean flying;     // helicopter
    private final int baseSpeedKmh;   // cruise speed; road vehicles 0..120, helicopters 180..259
    private final int maxSpeedKmh;    // clamp ceiling (higher for helicopters)

    /** Set for the geofence vehicle: circles this loop forever, no destination. */
    private final Route loop;

    private volatile Journey journey;
    private volatile boolean parked;
    private volatile boolean needsJourney;
    private double parkedSeconds;
    private double dwellSeconds;

    /** True while this journey's destination is a fuel station rather than a province. */
    private volatile boolean refuelTrip;
    /** How long to stand at the pump once there. */
    private double refuelDwellSeconds;
    /** Tank drain per minute of driving; 0 leaves the tank untouched (helicopters). */
    private volatile double fuelDrainPctPerMinute;

    private double distanceAlongKm;
    private double speedKmh;
    private double battery;
    private double fuelPct;
    private double odometerKm;
    private double lat;
    private double lon;
    private int heading;
    private final boolean engineOn = true;
    private final boolean ignition = true;

    private String region;

    /** Loop vehicle (geofence). */
    public VehicleState(String imei, BehaviorProfile profile, Route loop, long seed) {
        this(imei, profile, loop, null, seed, -1, false);
    }

    /** Loop vehicle with a fixed base speed (used by tests). */
    public VehicleState(String imei, BehaviorProfile profile, Route loop, long seed, int baseSpeedKmh) {
        this(imei, profile, loop, null, seed, baseSpeedKmh, false);
    }

    /** Journey vehicle (road): starts parked at {@code (lat, lon)}, asks for a destination. */
    public VehicleState(String imei, BehaviorProfile profile, double lat, double lon, long seed) {
        this(imei, profile, lat, lon, seed, -1);
    }

    public VehicleState(String imei, BehaviorProfile profile, double lat, double lon,
                        long seed, int baseSpeedKmh) {
        this(imei, profile, null, new double[]{lat, lon}, seed, baseSpeedKmh, false);
    }

    /** A helicopter: flying journeys from {@code (lat, lon)}, high speed, no road snap. */
    public static VehicleState helicopter(String imei, double lat, double lon, long seed) {
        return new VehicleState(imei, BehaviorProfile.NORMAL, null, new double[]{lat, lon},
                seed, -1, true);
    }

    private VehicleState(String imei, BehaviorProfile profile, Route loop, double[] start,
                         long seed, int baseSpeedKmh, boolean flying) {
        this.imei = imei;
        this.profile = profile;
        this.rnd = new Random(seed);
        this.flying = flying;
        if (flying) {
            this.baseSpeedKmh = 180 + rnd.nextInt(80);   // 180..259 km/h
            this.maxSpeedKmh = 300;
        } else {
            // Cruise speeds cluster in 35..105 so cars (limit 110) essentially never speed
            // while a moderate share of trucks (80) and motorcycles (90) do — enough to keep
            // the violation feed visible but far from the earlier per-second flood.
            this.baseSpeedKmh = baseSpeedKmh >= 0 ? Math.min(baseSpeedKmh, 120) : 35 + rnd.nextInt(71);
            this.maxSpeedKmh = 120;
        }
        // Helicopters are outside the fuel model entirely: they cannot use a roadside pump,
        // so draining them would strand them at zero with nowhere to go.
        this.fuelDrainPctPerMinute = flying ? 0.0 : DEFAULT_FUEL_DRAIN_PCT_PER_MINUTE;
        this.battery = profile == BehaviorProfile.LOW_BATTERY
                ? 22 + rnd.nextDouble() * 6 : 80 + rnd.nextDouble() * 20;
        this.fuelPct = 30 + rnd.nextDouble() * 70;
        this.odometerKm = rnd.nextDouble() * 100_000;
        this.loop = loop;
        if (loop != null) {
            this.distanceAlongKm = rnd.nextDouble() * loop.totalKm();
            GeoPoint p = loop.positionAt(distanceAlongKm);
            this.lat = p.lat();
            this.lon = p.lon();
        } else if (start != null) {
            this.lat = start[0];
            this.lon = start[1];
            this.needsJourney = true;
        }
    }

    /**
     * Put the vehicle on a new journey. {@code startProgress} (0..1) drops it somewhere
     * along the route rather than at the origin — at boot that staggers the fleet so
     * arrivals (and therefore trips) start flowing within minutes instead of hours.
     */
    public void startJourney(Journey journey, double startProgress) {
        this.journey = journey;
        this.distanceAlongKm = Math.max(0, Math.min(startProgress, 0.98)) * journey.totalKm();
        this.parked = false;
        this.needsJourney = false;
        this.parkedSeconds = 0;
        GeoPoint p = journey.route().positionAtClamped(distanceAlongKm);
        this.lat = p.lat();
        this.lon = p.lon();
    }

    /** Advance the simulation by {@code dtSeconds}. */
    public void tick(double dtSeconds) {
        if (loop != null) {
            tickLoop(dtSeconds);
            return;
        }
        tickJourney(dtSeconds);
    }

    private void tickLoop(double dtSeconds) {
        speedKmh = nextSpeed();
        double movedKm = speedKmh * dtSeconds / 3600.0;
        distanceAlongKm += movedKm;
        odometerKm += movedKm;
        GeoPoint p = loop.positionAt(distanceAlongKm);
        lat = p.lat();
        lon = p.lon();
        heading = (int) Math.round(loop.headingAt(distanceAlongKm));
        drain(dtSeconds);
    }

    private void tickJourney(double dtSeconds) {
        if (parked) {
            speedKmh = 0;
            parkedSeconds += dtSeconds;
            if (parkedSeconds >= dwellSeconds) {
                needsJourney = true;
            }
            return;
        }
        if (journey == null) {        // waiting for a route to be assigned
            speedKmh = 0;
            return;
        }
        if (isOutOfFuel()) {
            // A dry tank stops the vehicle where it stands. Without this it kept driving at
            // cruise speed on 0%, so the warning light blinked forever on a vehicle that was,
            // as far as the model was concerned, running on nothing.
            speedKmh = 0;
            return;
        }

        speedKmh = nextSpeed();
        double movedKm = speedKmh * dtSeconds / 3600.0;
        distanceAlongKm += movedKm;
        odometerKm += movedKm;

        if (distanceAlongKm >= journey.totalKm()) {          // arrived
            distanceAlongKm = journey.totalKm();
            GeoPoint end = journey.route().positionAtClamped(distanceAlongKm);
            lat = end.lat();
            lon = end.lon();
            speedKmh = 0;
            parked = true;
            parkedSeconds = 0;
            if (refuelTrip) {
                // At the pump: fill on arrival, then stand for the refuel dwell. Filling here
                // rather than when the dwell ends keeps the tank and the map honest — the
                // low-fuel warning clears the moment the vehicle actually reaches fuel, and no
                // ordering window exists where a full-looking vehicle is sent to refuel again.
                fuelPct = FULL_TANK_PCT;
                refuelTrip = false;
                dwellSeconds = refuelDwellSeconds;
            } else {
                dwellSeconds = DWELL_MIN_SECONDS
                        + rnd.nextInt(DWELL_MAX_SECONDS - DWELL_MIN_SECONDS);
            }
            return;
        }

        GeoPoint p = journey.route().positionAtClamped(distanceAlongKm);
        lat = p.lat();
        lon = p.lon();
        heading = (int) Math.round(journey.route().headingAtClamped(distanceAlongKm));
        drain(dtSeconds);
    }

    private void drain(double dtSeconds) {
        double batteryDrain = (profile == BehaviorProfile.LOW_BATTERY ? 0.05 : 0.0005) * dtSeconds;
        battery = clamp(battery - batteryDrain, 0, 100);
        fuelPct = clamp(fuelPct - fuelDrainPctPerMinute * dtSeconds / 60.0, 0, 100);
    }

    private double nextSpeed() {
        if (flying) {
            double noise = (rnd.nextDouble() - 0.5) * 16;
            return clamp(baseSpeedKmh + noise, 0, maxSpeedKmh);
        }
        // Harsh-braking vehicles still inject the occasional sudden deceleration.
        if (profile == BehaviorProfile.HARSH_BRAKING && rnd.nextDouble() < 0.06) {
            return clamp(speedKmh - (45 + rnd.nextDouble() * 10), 0, maxSpeedKmh);
        }
        // Every road vehicle cruises around its own random base speed (0..120 km/h);
        // whether that breaches the limit is decided per vehicle type in the rules.
        double noise = (rnd.nextDouble() - 0.5) * 8;
        return clamp(baseSpeedKmh + noise, 0, maxSpeedKmh);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ── Journey / dispatch view ────────────────────────────────────────────
    public boolean needsJourney() {
        return needsJourney;
    }

    public boolean isParked() {
        return parked;
    }

    public boolean isFlying() {
        return flying;
    }

    public Journey journey() {
        return journey;
    }

    public String destination() {
        Journey j = journey;
        return j == null ? null : j.destination();
    }

    public Double destLat() {
        Journey j = journey;
        return j == null ? null : j.destLat();
    }

    public Double destLon() {
        Journey j = journey;
        return j == null ? null : j.destLon();
    }

    /** Real remaining road (or flight) distance to the destination, in km. */
    public double remainingKm() {
        Journey j = journey;
        if (j == null) {
            return 0;
        }
        return Math.max(0, j.totalKm() - distanceAlongKm);
    }

    /** Minutes to arrival at the current cruise speed; -1 when parked or stopped. */
    public int etaMinutes() {
        if (parked || journey == null || speedKmh < 1) {
            return -1;
        }
        return (int) Math.round(remainingKm() / speedKmh * 60);
    }

    /** The path still ahead (current position -> destination) for the UI to draw. */
    public List<GeoPoint> remainingRoute() {
        Journey j = journey;
        if (j == null) {
            return List.of();
        }
        return j.route().remainingFrom(distanceAlongKm);
    }

    public boolean isLoopVehicle() {
        return loop != null;
    }

    // ── Telemetry view ─────────────────────────────────────────────────────
    public String imei() {
        return imei;
    }

    public double lat() {
        return lat;
    }

    public double lon() {
        return lon;
    }

    public int speedKmh() {
        return (int) Math.round(speedKmh);
    }

    public int heading() {
        return heading;
    }

    public int battery() {
        return (int) Math.round(battery);
    }

    public int fuelPct() {
        return (int) Math.round(fuelPct);
    }

    public long odometerKm() {
        return (long) odometerKm;
    }

    public boolean engineOn() {
        return engineOn;
    }

    public boolean ignition() {
        return ignition;
    }

    // ── Fuel ───────────────────────────────────────────────────────────────

    /**
     * Send this vehicle to a fuel station. Identical to any other journey except that the tank
     * is filled on arrival and the stop is short, after which it asks for a normal destination.
     */
    public void startRefuelJourney(Journey toStation, double dwellAtPumpSeconds) {
        startJourney(toStation, 0.0);
        this.refuelTrip = true;
        this.refuelDwellSeconds = dwellAtPumpSeconds;
    }

    /** True while this vehicle is on its way to a pump, so it is not dispatched twice. */
    public boolean isSeekingFuel() {
        return refuelTrip;
    }

    /**
     * Adopt a tank level measured elsewhere. Used when a real fuel-data source has a reading
     * for this vehicle; it overrides the simulated tank for as long as readings keep arriving.
     */
    public void overrideFuelPct(double pct) {
        this.fuelPct = clamp(pct, 0, 100);
    }

    /** Tank drain per minute of driving. Zero keeps the tank full (helicopters). */
    public void setFuelDrainPctPerMinute(double pctPerMinute) {
        this.fuelDrainPctPerMinute = Math.max(0, pctPerMinute);
    }

    /** True when this vehicle burns fuel at all — false for aircraft, which are outside the model. */
    public boolean usesFuel() {
        return fuelDrainPctPerMinute > 0;
    }

    /** Dry tank: the vehicle is stopped wherever it stands until something refuels it. */
    public boolean isOutOfFuel() {
        return usesFuel() && fuelPct <= 0;
    }

    // ── Operator-console control ───────────────────────────────────────────


    public void setRegion(String region) {
        this.region = region;
    }

    public String region() {
        return region;
    }
}
