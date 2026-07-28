package com.fleet.vts.iettfeed.mapping;

/**
 * Derived motion for a reading. Fields are {@code null} when they cannot be
 * computed yet (e.g. the first time a vehicle is seen).
 */
public record Motion(Integer speedKmh, Integer heading) {

    public static final Motion UNKNOWN = new Motion(null, null);
}
