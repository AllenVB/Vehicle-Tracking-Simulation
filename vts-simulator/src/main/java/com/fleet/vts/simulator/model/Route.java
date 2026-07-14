package com.fleet.vts.simulator.model;

import com.fleet.vts.simulator.sim.GeoUtils;

import java.util.List;

/**
 * A polyline a vehicle travels along. Precomputes cumulative segment distances so a
 * vehicle can be positioned by "distance travelled along the route".
 *
 * <p>Two shapes:
 * <ul>
 *   <li><b>closed</b> — the last point joins back to the first and distance wraps at
 *       the end. Used for the geofence vehicle's loop.</li>
 *   <li><b>open</b> — an A-to-B journey. The closing segment does NOT exist, so
 *       {@code totalKm()} is the true road distance and a vehicle that has covered it
 *       sits at the destination instead of driving a straight line back to the start.
 *       Getting this wrong inflates "remaining km" and makes arrivals never happen.</li>
 * </ul>
 */
public class Route {

    private final List<GeoPoint> points;
    private final double[] cumulative; // cumulative[i] = distance to start of segment i
    private final double totalKm;
    private final boolean closed;

    /** A closed loop (wraps at the end). */
    public Route(List<GeoPoint> points) {
        this(points, true);
    }

    public Route(List<GeoPoint> points, boolean closed) {
        if (points.size() < 2) {
            throw new IllegalArgumentException("A route needs at least 2 points");
        }
        this.points = points;
        this.closed = closed;
        int n = points.size();
        int segments = closed ? n : n - 1;
        this.cumulative = new double[segments + 1];
        double acc = 0;
        for (int i = 0; i < segments; i++) {
            cumulative[i] = acc;
            acc += GeoUtils.haversineKm(points.get(i), points.get((i + 1) % n));
        }
        cumulative[segments] = acc;
        this.totalKm = acc;
    }

    public double totalKm() {
        return totalKm;
    }

    /** Position on a closed loop; distance wraps at the end. */
    public GeoPoint positionAt(double distanceKm) {
        return positionRaw(wrap(distanceKm));
    }

    /** Heading on a closed loop; distance wraps at the end. */
    public double headingAt(double distanceKm) {
        return headingRaw(wrap(distanceKm));
    }

    /** Position on an A-to-B journey: distance is clamped, so the end is the destination. */
    public GeoPoint positionAtClamped(double distanceKm) {
        return positionRaw(clamp(distanceKm));
    }

    /** Heading on an A-to-B journey (clamped, see {@link #positionAtClamped}). */
    public double headingAtClamped(double distanceKm) {
        return headingRaw(clamp(distanceKm));
    }

    private GeoPoint positionRaw(double d) {
        int seg = segmentIndex(d);
        GeoPoint a = points.get(seg);
        GeoPoint b = points.get((seg + 1) % points.size());
        double segLen = cumulative[seg + 1] - cumulative[seg];
        double f = segLen == 0 ? 0 : (d - cumulative[seg]) / segLen;
        return GeoUtils.interpolate(a, b, f);
    }

    private double headingRaw(double d) {
        int seg = segmentIndex(d);
        return GeoUtils.bearingDeg(points.get(seg), points.get((seg + 1) % points.size()));
    }

    private int segmentIndex(double d) {
        int segments = cumulative.length - 1;
        for (int i = 0; i < segments; i++) {
            if (d >= cumulative[i] && d < cumulative[i + 1]) {
                return i;
            }
        }
        return segments - 1;
    }

    private double wrap(double d) {
        if (totalKm == 0) {
            return 0;
        }
        double r = d % totalKm;
        return r < 0 ? r + totalKm : r;
    }

    private double clamp(double d) {
        if (totalKm == 0) {
            return 0;
        }
        return Math.max(0, Math.min(d, totalKm - 1e-9));
    }
}
