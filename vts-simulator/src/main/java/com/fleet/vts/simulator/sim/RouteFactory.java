package com.fleet.vts.simulator.sim;

import com.fleet.vts.simulator.model.GeoPoint;
import com.fleet.vts.simulator.model.Route;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the 20 route polylines around Istanbul. Index 0 is a loop inside the
 * seeded restricted geofence (Tarihi Yarımada), used by the geofence-anomaly
 * vehicles; indices 1..19 are ordinary scattered loops.
 */
public final class RouteFactory {

    public static final int ROUTE_COUNT = 20;
    private static final int WAYPOINTS = 12;

    private RouteFactory() {
    }

    public static List<Route> build() {
        List<Route> routes = new ArrayList<>(ROUTE_COUNT);
        // Index 0: inside the restricted polygon (lon 28.955..28.985, lat 41.00..41.02).
        routes.add(loop(41.010, 28.970, 0.006, 0.007));
        for (int k = 1; k < ROUTE_COUNT; k++) {
            double centerLat = 40.95 + 0.012 * (k % 6);
            double centerLon = 28.90 + 0.030 * (k % 8);
            double radius = 0.018 + 0.004 * (k % 4);
            routes.add(loop(centerLat, centerLon, radius, radius));
        }
        return routes;
    }

    private static Route loop(double centerLat, double centerLon, double radiusLat, double radiusLon) {
        List<GeoPoint> pts = new ArrayList<>(WAYPOINTS);
        for (int j = 0; j < WAYPOINTS; j++) {
            double angle = 2 * Math.PI * j / WAYPOINTS;
            pts.add(new GeoPoint(centerLat + radiusLat * Math.sin(angle),
                    centerLon + radiusLon * Math.cos(angle)));
        }
        return new Route(pts);
    }
}
