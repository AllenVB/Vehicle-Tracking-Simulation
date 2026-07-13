package com.fleet.vts.simulator.model;

import java.util.Random;

/**
 * Mutable per-vehicle simulation state. Each tick advances the vehicle along its
 * route by speed*dt and updates battery/fuel/odometer. The speed is shaped by
 * the {@link BehaviorProfile} to inject the intended anomalies.
 *
 * <p>Not thread-safe by design: each vehicle is ticked by exactly one virtual
 * thread per cycle, so there is no shared mutation.
 */
public class VehicleState {

    private final String imei;
    private final BehaviorProfile profile;
    private final Route route;
    private final Random rnd;

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

    public VehicleState(String imei, BehaviorProfile profile, Route route, long seed) {
        this.imei = imei;
        this.profile = profile;
        this.route = route;
        this.rnd = new Random(seed);
        this.distanceAlongKm = rnd.nextDouble() * route.totalKm();
        this.battery = profile == BehaviorProfile.LOW_BATTERY
                ? 22 + rnd.nextDouble() * 6 : 80 + rnd.nextDouble() * 20;
        this.fuelPct = 30 + rnd.nextDouble() * 70;
        this.odometerKm = rnd.nextDouble() * 100_000;
        GeoPoint p = route.positionAt(distanceAlongKm);
        this.lat = p.lat();
        this.lon = p.lon();
    }

    /** Advance the simulation by {@code dtSeconds}. */
    public void tick(double dtSeconds) {
        speedKmh = nextSpeed();
        double movedKm = speedKmh * dtSeconds / 3600.0;
        distanceAlongKm += movedKm;
        odometerKm += movedKm;
        GeoPoint p = route.positionAt(distanceAlongKm);
        lat = p.lat();
        lon = p.lon();
        heading = (int) Math.round(route.headingAt(distanceAlongKm));
        double batteryDrain = (profile == BehaviorProfile.LOW_BATTERY ? 0.05 : 0.0005) * dtSeconds;
        battery = clamp(battery - batteryDrain, 0, 100);
        fuelPct = clamp(fuelPct - 0.005 * dtSeconds, 0, 100);
    }

    private double nextSpeed() {
        double base;
        switch (profile) {
            case SPEEDER -> base = 95;          // always above the 80 km/h limit
            case GEOFENCE -> base = 50;
            case LOW_BATTERY -> base = 40;
            case HARSH_BRAKING -> {
                if (rnd.nextDouble() < 0.15) {  // sudden >45 km/h drop
                    return clamp(speedKmh - (45 + rnd.nextDouble() * 10), 0, 120);
                }
                base = 60;
            }
            default -> base = 45;
        }
        double noise = (rnd.nextDouble() - 0.5) * 10;
        return clamp(base + noise, 0, 120);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

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
}
