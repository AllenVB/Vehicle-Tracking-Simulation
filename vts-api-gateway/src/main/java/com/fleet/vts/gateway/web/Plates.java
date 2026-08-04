package com.fleet.vts.gateway.web;

import java.util.Locale;

/**
 * Plate normalization: strip all whitespace and upper-case, so "06 ANK 06", "06ANK06",
 * "06 ANK06" and "06ANK 06" are the same plate. Applied on every write (create/update) and when
 * a driver signs in, so a spacing typo can neither create a duplicate vehicle nor block a login.
 */
public final class Plates {

    private Plates() {
    }

    public static String normalize(String plate) {
        return plate == null ? null : plate.replaceAll("\\s+", "").toUpperCase(Locale.ENGLISH);
    }
}
