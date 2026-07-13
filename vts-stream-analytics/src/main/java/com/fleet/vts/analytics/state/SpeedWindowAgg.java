package com.fleet.vts.analytics.state;

import com.fleet.vts.common.event.TelemetryEvent;

/**
 * Accumulator for the sustained-speeding hopping window: how many readings fell
 * in the window and how many exceeded the speed limit.
 */
public record SpeedWindowAgg(Long tenantId, int total, int over, Double lastLat, Double lastLon) {

    public static SpeedWindowAgg empty() {
        return new SpeedWindowAgg(null, 0, 0, null, null);
    }

    public SpeedWindowAgg add(TelemetryEvent e, double limit) {
        int speed = e.speedKmh() == null ? 0 : e.speedKmh();
        return new SpeedWindowAgg(
                e.tenantId(),
                total + 1,
                over + (speed > limit ? 1 : 0),
                e.lat(),
                e.lon());
    }

    public double ratioOver() {
        return total == 0 ? 0 : (double) over / total;
    }
}
