package com.fleet.vts.simulator.fuel;

import java.util.OptionalDouble;

/**
 * Where a vehicle's tank level comes from.
 *
 * <p>This is the seam for real fuel data. Today the level is simulated (see
 * {@link SimulatedFuelLevelSource}); when a real fleet or telematics API becomes available,
 * implement this against it and register it as a bean — nothing else has to change, because
 * every consumer of a fuel reading already goes through here.
 *
 * <p>A reading is optional per vehicle rather than per source, so a real integration can
 * cover part of the fleet: vehicles it knows report real levels while the rest stay on the
 * simulated tank. That matters during a rollout, when only some vehicles have the sensor
 * fitted.
 *
 * <p>Implementations are called once per vehicle per tick, so they must not block on I/O —
 * poll the upstream system on a schedule and answer from a cached snapshot.
 */
public interface FuelLevelSource {

    /**
     * The tank level (0..100) to report for this vehicle, or empty when this source has no
     * reading for it — in which case the simulated tank stands.
     */
    OptionalDouble readFuelPct(String imei);
}
