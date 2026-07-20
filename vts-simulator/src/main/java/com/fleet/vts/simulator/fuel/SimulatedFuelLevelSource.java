package com.fleet.vts.simulator.fuel;

import java.util.OptionalDouble;

/**
 * The default source: it has no external reading for anyone, so every vehicle falls back to
 * the tank the simulator drains itself.
 *
 * <p>Empty rather than a made-up number on purpose. "No reading" and "the tank is at 0" are
 * different facts, and a source that invented values would make a real integration's gaps
 * invisible.
 */
public class SimulatedFuelLevelSource implements FuelLevelSource {

    @Override
    public OptionalDouble readFuelPct(String imei) {
        return OptionalDouble.empty();
    }
}
