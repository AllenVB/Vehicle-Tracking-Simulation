package com.fleet.vts.simulator.model;

/**
 * A vehicle's current assignment: a named destination and the real driving route
 * (OSRM road geometry) that leads there. {@code route.totalKm()} is the real road
 * distance, so "remaining km" reported to the UI is genuine, not a straight line.
 */
public record Journey(String destination, double destLat, double destLon, Route route) {

    public double totalKm() {
        return route.totalKm();
    }
}
