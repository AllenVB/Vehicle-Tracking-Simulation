package com.fleet.vts.gateway.web.dto;

import java.math.BigDecimal;

/** A driver's standing on the scoreboard, averaged over the requested window. */
public record DriverScoreDto(
        Long driverId,
        String name,
        BigDecimal score,
        BigDecimal distanceKm,
        Long violationCount,
        long daysScored) {
}
