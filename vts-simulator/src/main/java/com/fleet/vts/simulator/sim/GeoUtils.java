package com.fleet.vts.simulator.sim;

import com.fleet.vts.simulator.model.GeoPoint;

/** Small great-circle helpers for moving vehicles along routes. */
public final class GeoUtils {

    private static final double EARTH_RADIUS_KM = 6371.0088;

    private GeoUtils() {
    }

    /** Great-circle distance in kilometres. */
    public static double haversineKm(GeoPoint a, GeoPoint b) {
        double dLat = Math.toRadians(b.lat() - a.lat());
        double dLon = Math.toRadians(b.lon() - a.lon());
        double lat1 = Math.toRadians(a.lat());
        double lat2 = Math.toRadians(b.lat());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1.0, Math.sqrt(h)));
    }

    /** Initial bearing from a to b, in degrees [0,360). */
    public static double bearingDeg(GeoPoint a, GeoPoint b) {
        double lat1 = Math.toRadians(a.lat());
        double lat2 = Math.toRadians(b.lat());
        double dLon = Math.toRadians(b.lon() - a.lon());
        double y = Math.sin(dLon) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2)
                - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);
        double deg = Math.toDegrees(Math.atan2(y, x));
        return (deg + 360.0) % 360.0;
    }

    /** Linear interpolation between two nearby points (adequate for short segments). */
    public static GeoPoint interpolate(GeoPoint a, GeoPoint b, double f) {
        return new GeoPoint(a.lat() + (b.lat() - a.lat()) * f,
                a.lon() + (b.lon() - a.lon()) * f);
    }
}
