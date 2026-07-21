package com.fleet.vts.gateway.web.dto;

import java.math.BigDecimal;

/**
 * A driver's standing on the scoreboard: the average of their journey scores (1..10) over the
 * requested window, with the distance and violations those journeys covered.
 */
public record DriverScoreDto(
        Long driverId,
        String name,
        BigDecimal score,
        BigDecimal distanceKm,
        Long violationCount,
        long tripsScored) {
}
