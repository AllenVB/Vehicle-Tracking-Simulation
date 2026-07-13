package com.fleet.vts.gateway.live;

/** A viewport bounding box sent by the client; only vehicles inside are pushed. */
public record Bbox(double minLat, double minLon, double maxLat, double maxLon) {

    public boolean contains(Position p) {
        if (p.lat() == null || p.lon() == null) {
            return false;
        }
        return p.lat() >= minLat && p.lat() <= maxLat
                && p.lon() >= minLon && p.lon() <= maxLon;
    }
}
