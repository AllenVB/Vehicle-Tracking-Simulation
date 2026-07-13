package com.fleet.vts.analytics.state;

import java.util.Set;

/** The set of geofence ids a vehicle is currently inside. */
public record GeofenceState(Set<Long> insideIds) {

    public static GeofenceState empty() {
        return new GeofenceState(Set.of());
    }
}
