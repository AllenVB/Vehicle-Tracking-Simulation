package com.fleet.vts.simulator.model;

import com.fleet.vts.simulator.sim.GeoUtils;

import java.util.List;

/**
 * A closed polyline the vehicles loop around. Precomputes cumulative segment
 * distances so a vehicle can be positioned by "distance travelled along the
 * route" and wrap seamlessly at the end.
 */
public class Route {

    private final List<GeoPoint> points;
    private final double[] cumulative; // cumulative[i] = distance to start of segment i
    private final double totalKm;

    public Route(List<GeoPoint> points) {
        if (points.size() < 2) {
            throw new IllegalArgumentException("A route needs at least 2 points");
        }
        this.points = points;
        int n = points.size();
        this.cumulative = new double[n + 1];
        double acc = 0;
        for (int i = 0; i < n; i++) {
            cumulative[i] = acc;
            acc += GeoUtils.haversineKm(points.get(i), points.get((i + 1) % n));
        }
        cumulative[n] = acc;
        this.totalKm = acc;
    }

    public double totalKm() {
        return totalKm;
    }

    public GeoPoint positionAt(double distanceKm) {
        double d = wrap(distanceKm);
        int seg = segmentIndex(d);
        GeoPoint a = points.get(seg);
        GeoPoint b = points.get((seg + 1) % points.size());
        double segLen = cumulative[seg + 1] - cumulative[seg];
        double f = segLen == 0 ? 0 : (d - cumulative[seg]) / segLen;
        return GeoUtils.interpolate(a, b, f);
    }

    public double headingAt(double distanceKm) {
        double d = wrap(distanceKm);
        int seg = segmentIndex(d);
        return GeoUtils.bearingDeg(points.get(seg), points.get((seg + 1) % points.size()));
    }

    private int segmentIndex(double d) {
        for (int i = 0; i < points.size(); i++) {
            if (d >= cumulative[i] && d < cumulative[i + 1]) {
                return i;
            }
        }
        return points.size() - 1;
    }

    private double wrap(double d) {
        if (totalKm == 0) {
            return 0;
        }
        double r = d % totalKm;
        return r < 0 ? r + totalKm : r;
    }
}
