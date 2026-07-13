package com.fleet.vts.simulator.model;

/**
 * Deterministic behaviour buckets that inject the intended anomalies:
 * ~10% speeders, ~5% harsh brakers, ~3% restricted-zone drivers, ~2% low battery.
 */
public enum BehaviorProfile {
    NORMAL,
    SPEEDER,
    HARSH_BRAKING,
    GEOFENCE,
    LOW_BATTERY;

    /** Priority-ordered assignment so buckets do not overlap. */
    public static BehaviorProfile forIndex(int oneBasedIndex) {
        if (oneBasedIndex % 50 == 0) {
            return LOW_BATTERY;   // ~2%
        }
        if (oneBasedIndex % 33 == 0) {
            return GEOFENCE;      // ~3%
        }
        if (oneBasedIndex % 20 == 0) {
            return HARSH_BRAKING; // ~5%
        }
        if (oneBasedIndex % 10 == 0) {
            return SPEEDER;       // ~10%
        }
        return NORMAL;
    }
}
