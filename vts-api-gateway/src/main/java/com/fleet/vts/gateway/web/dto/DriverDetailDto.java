package com.fleet.vts.gateway.web.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Everything the driver-detail view needs in one call: the driver's standing (rank via the Redis
 * leaderboard), their window aggregates, their recent trips, and the day-by-day score trend for
 * the sparkline. {@code rank}/{@code total} are null when the driver has no scored trip in the
 * window, so the view can say "henüz sıralanmadı" instead of inventing a place.
 */
public record DriverDetailDto(
        Long driverId,
        String name,
        Integer rank,
        Integer total,
        BigDecimal avgScore,
        BigDecimal distanceKm,
        Long violationCount,
        long tripsScored,
        List<TripSummaryDto> trips,
        List<DailyPointDto> scoreByDay) {
}
