package com.fleet.vts.simulator.model;

import java.util.Random;

/**
 * Mutable per-vehicle simulation state.
 *
 * <p>Two movement modes:
 * <ul>
 *   <li><b>Journey</b> (the norm): the vehicle drives a real OSRM road route toward a
 *       named destination, parks on arrival for a dwell, then asks for a new journey.
 *       The dwell is longer than the trip-detection stop window, so every arrival
 *       actually closes a trip — which is what fills {@code trip} / {@code trip_point}
 *       and gives driver scoring something to score.</li>
 *   <li><b>Loop</b>: a closed polyline the vehicle circles forever. Used only for the
 *       geofence-anomaly vehicle, which must stay inside the restricted zone.</li>
 * </ul>
 *
 * <p>Not thread-safe by design: each vehicle is ticked by exactly one virtual thread
 * per cycle. Fields written from the HTTP/journey threads are {@code volatile}.
 */
public class VehicleState {

    /** Park long enough on arrival that the 5-minute trip-stop window elapses. */
    private static final int DWELL_MIN_SECONDS = 330;   // 5.5 dk
    private static final int DWELL_MAX_SECONDS = 720;   // 12 dk

    private final String imei;
    private final BehaviorProfile profile;
    private final Random rnd;
    private final int baseSpeedKmh;   // her aracın rastgele seyir hızı, 0..120 km/s

    /** Set for the geofence vehicle: circles this loop forever, no destination. */
    private final Route loop;

    private volatile Journey journey;
    private volatile boolean parked;
    private volatile boolean needsJourney;
    private double parkedSeconds;
    private double dwellSeconds;

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

    // Manual override from the operator console.
    private volatile boolean manual;
    private volatile double manualLat;
    private volatile double manualLon;
    private String region;

    /** Loop vehicle (geofence). */
    public VehicleState(String imei, BehaviorProfile profile, Route loop, long seed) {
        this(imei, profile, loop, seed, -1);
    }

    public VehicleState(String imei, BehaviorProfile profile, Route loop, long seed, int baseSpeedKmh) {
        this.imei = imei;
        this.profile = profile;
        this.rnd = new Random(seed);
        this.baseSpeedKmh = baseSpeedKmh >= 0 ? Math.min(baseSpeedKmh, 120) : rnd.nextInt(121);
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
        }
    }

    /** Journey vehicle: starts parked at {@code (lat, lon)} and asks for a destination. */
    public VehicleState(String imei, BehaviorProfile profile, double lat, double lon, long seed) {
        this(imei, profile, lat, lon, seed, -1);
    }

    public VehicleState(String imei, BehaviorProfile profile, double lat, double lon,
                        long seed, int baseSpeedKmh) {
        this(imei, profile, (Route) null, seed, baseSpeedKmh);
        this.lat = lat;
        this.lon = lon;
        this.needsJourney = true;
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
        if (manual) {                 // operator console is holding this vehicle in place
            speedKmh = 0;
            lat = manualLat;
            lon = manualLon;
            return;
        }
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
        // Parked at the destination: engine on, speed 0 -> the trip closes and, if the
        // dwell runs long, the idling rule fires. Then we ask for the next destination.
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
            dwellSeconds = DWELL_MIN_SECONDS + rnd.nextInt(DWELL_MAX_SECONDS - DWELL_MIN_SECONDS);
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
        fuelPct = clamp(fuelPct - 0.005 * dtSeconds, 0, 100);
    }

    private double nextSpeed() {
        // Harsh-braking vehicles still inject sudden decelerations.
        if (profile == BehaviorProfile.HARSH_BRAKING && rnd.nextDouble() < 0.15) {
            return clamp(speedKmh - (45 + rnd.nextDouble() * 10), 0, 120);
        }
        // Every vehicle cruises around its own random base speed (0..120 km/h);
        // whether that breaches the limit is decided per vehicle type in the rules.
        double noise = (rnd.nextDouble() - 0.5) * 8;
        return clamp(baseSpeedKmh + noise, 0, 120);
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

    public Journey journey() {
        return journey;
    }

    public String destination() {
        Journey j = journey;
        return j == null ? null : j.destination();
    }

    /** Real remaining road distance to the destination, in km. */
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

    // ── Operator-console control ───────────────────────────────────────────
    public void setManual(double lat, double lon) {
        this.manualLat = lat;
        this.manualLon = lon;
        this.manual = true;
    }

    public void clearManual() {
        this.manual = false;
    }

    public boolean isManual() {
        return manual;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String region() {
        return region;
    }
}
