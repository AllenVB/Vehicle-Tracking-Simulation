package com.fleet.vts.iettfeed.mapping;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Derives speed and heading from successive positions of the same vehicle, since
 * the İETT feed provides neither. Speed = haversine distance / elapsed time
 * (clamped 0–400 km/h); heading = compass bearing between the two points. Small
 * jitter under {@value #MIN_MOVE_METERS} m is treated as stationary so a parked
 * bus does not spin its heading on GPS noise.
 */
@Component
public class MotionTracker {

    private static final double MIN_MOVE_METERS = 8.0;
    private static final double EARTH_RADIUS_M = 6_371_000.0;

    private record Sample(double lat, double lon, Instant ts, Integer heading) {
    }

    private final ConcurrentMap<String, Sample> last = new ConcurrentHashMap<>();

    public Motion update(String vehicleId, double lat, double lon, Instant ts) {
        Sample prev = last.get(vehicleId);
        if (prev == null) {
            last.put(vehicleId, new Sample(lat, lon, ts, null));
            return Motion.UNKNOWN;
        }

        double meters = haversineMeters(prev.lat(), prev.lon(), lat, lon);
        boolean moved = meters >= MIN_MOVE_METERS;
        // Keep both branches Integer: a mixed int/Integer ternary unboxes prev.heading(),
        // which is null until the first real move, and would NPE on a sub-threshold nudge.
        Integer heading = prev.heading();
        if (moved) {
            heading = bearing(prev.lat(), prev.lon(), lat, lon);
        }

        Integer speedKmh = null;
        if (prev.ts() != null && ts != null) {
            long dtSec = ts.getEpochSecond() - prev.ts().getEpochSecond();
            if (dtSec > 0) {
                speedKmh = moved ? clampSpeed(Math.round(meters * 3.6 / dtSec)) : 0;
            }
        }

        last.put(vehicleId, new Sample(lat, lon, ts, heading));
        return new Motion(speedKmh, heading);
    }

    private static int clampSpeed(long kmh) {
        return (int) Math.max(0, Math.min(400, kmh));
    }

    /** Great-circle distance in metres. */
    static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return EARTH_RADIUS_M * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** Initial compass bearing from point 1 to point 2, normalised to 0–359. */
    static int bearing(double lat1, double lon1, double lat2, double lon2) {
        double dLon = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dLon) * Math.cos(Math.toRadians(lat2));
        double x = Math.cos(Math.toRadians(lat1)) * Math.sin(Math.toRadians(lat2))
                - Math.sin(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.cos(dLon);
        double deg = Math.toDegrees(Math.atan2(y, x));
        return (int) ((Math.round(deg) % 360 + 360) % 360);
    }
}
