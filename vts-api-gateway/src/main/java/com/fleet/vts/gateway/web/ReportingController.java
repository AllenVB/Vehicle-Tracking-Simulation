package com.fleet.vts.gateway.web;

import com.fleet.vts.gateway.cache.DriverLeaderboardCache;
import com.fleet.vts.gateway.repository.ReportingQueryRepository;
import com.fleet.vts.gateway.security.CurrentUser;
import com.fleet.vts.gateway.web.dto.DashboardSummaryDto;
import com.fleet.vts.gateway.web.dto.DriverDetailDto;
import com.fleet.vts.gateway.web.dto.DriverRankDto;
import com.fleet.vts.gateway.web.dto.DriverScoreDto;
import com.fleet.vts.gateway.web.dto.FleetAnalyticsDto;
import com.fleet.vts.gateway.web.dto.FuelStationDto;
import com.fleet.vts.gateway.web.dto.GeofenceDto;
import com.fleet.vts.gateway.web.dto.TelemetryBucketDto;
import com.fleet.vts.gateway.web.dto.TripPointDto;
import com.fleet.vts.gateway.web.dto.TripSummaryDto;
import com.fleet.vts.gateway.web.dto.VehicleCategoryDto;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Dashboard summary plus read-optimised time-series endpoints. Telemetry history
 * is served from the continuous aggregate ({@code telemetry_1min}), never the
 * raw hypertable; trip routes are rebuilt from {@code trip_point}.
 *
 * <p>The queries live in {@link ReportingQueryRepository}; this class only resolves the
 * caller's tenant, bounds the paging parameters and returns the result.
 */
@RestController
@RequestMapping("/api/v1")
public class ReportingController {

    /**
     * Upper bound on the scoreboard page.
     *
     * <p>Above the fleet's driver count on purpose: the live map's popup shows each vehicle's
     * driver score, and it gets them by pulling the whole scoreboard once rather than asking
     * per driver. At the previous cap of 100 against 200 drivers, half the fleet's popups
     * reported "no score yet" for drivers who had one.
     */
    private static final int MAX_DRIVER_SCORES = 500;

    private final ReportingQueryRepository reporting;
    private final DriverLeaderboardCache leaderboard;

    public ReportingController(ReportingQueryRepository reporting, DriverLeaderboardCache leaderboard) {
        this.reporting = reporting;
        this.leaderboard = leaderboard;
    }

    @GetMapping("/dashboard/summary")
    public DashboardSummaryDto summary(@AuthenticationPrincipal Jwt jwt) {
        return reporting.findDashboardSummary(CurrentUser.tenantId(jwt));
    }

    @GetMapping("/vehicles/{id}/telemetry")
    public List<TelemetryBucketDto> telemetry(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                              @RequestParam Instant from, @RequestParam Instant to) {
        return reporting.findTelemetryBuckets(CurrentUser.tenantId(jwt), id, from, to);
    }

    /**
     * Active geofences as GeoJSON, so the map can actually show the zones it keeps
     * raising enter/exit events for.
     */
    @GetMapping("/geofences")
    public List<GeofenceDto> geofences(@AuthenticationPrincipal Jwt jwt) {
        return reporting.findActiveGeofences(CurrentUser.tenantId(jwt));
    }

    /**
     * The fleet taxonomy: land / air / sea, and the types under each. Categories with no
     * types are included — SEA is registered and empty on purpose.
     */
    @GetMapping("/vehicle-types")
    public List<VehicleCategoryDto> vehicleTypes(@AuthenticationPrincipal Jwt jwt) {
        return reporting.findVehicleTaxonomy(CurrentUser.tenantId(jwt));
    }

    /** Fuel stations (for the map and the nearest-station distance shown on select). */
    @GetMapping("/fuel-stations")
    public List<FuelStationDto> fuelStations(@AuthenticationPrincipal Jwt jwt) {
        return reporting.findFuelStations(CurrentUser.tenantId(jwt));
    }

    /**
     * Driver scoreboard: best drivers over the window, by average journey score. Served from the
     * Redis leaderboard cache ({@link DriverLeaderboardCache}) — a single sorted-list read when
     * warm, the {@code trip} aggregate only on a cold window.
     */
    @GetMapping("/drivers/scores")
    public List<DriverScoreDto> driverScores(@AuthenticationPrincipal Jwt jwt,
                                             @RequestParam(defaultValue = "30") int days,
                                             @RequestParam(defaultValue = "20") int limit) {
        int capped = Math.clamp(limit, 1, MAX_DRIVER_SCORES);
        return leaderboard.topDrivers(CurrentUser.tenantId(jwt), days, capped);
    }

    /**
     * One driver's standing on the board: rank (1-based) out of everyone scored in the window,
     * via a {@code ZREVRANK} on the leaderboard sorted set. 204 when the driver has no scored
     * trip in the window, so the UI can say "henüz sıralanmadı" rather than invent a rank.
     */
    @GetMapping("/drivers/{id}/rank")
    public org.springframework.http.ResponseEntity<DriverRankDto> driverRank(
            @AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
            @RequestParam(defaultValue = "30") int days) {
        DriverRankDto rank = leaderboard.rank(CurrentUser.tenantId(jwt), days, id);
        return rank == null
                ? org.springframework.http.ResponseEntity.noContent().build()
                : org.springframework.http.ResponseEntity.ok(rank);
    }

    @GetMapping("/vehicles/{id}/trips")
    public List<TripSummaryDto> vehicleTrips(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                             @RequestParam(defaultValue = "20") int limit) {
        return reporting.findVehicleTrips(CurrentUser.tenantId(jwt), id, limit);
    }

    /** Fleet analytics time series for the charts panel: violations/day and average score/day. */
    @GetMapping("/analytics/daily")
    public FleetAnalyticsDto analyticsDaily(@AuthenticationPrincipal Jwt jwt,
                                            @RequestParam(defaultValue = "14") int days) {
        int d = Math.clamp(days, 1, 90);
        long tenant = CurrentUser.tenantId(jwt);
        return new FleetAnalyticsDto(reporting.findViolationsDaily(tenant, d),
                reporting.findScoreDaily(tenant, d));
    }

    /**
     * Everything the driver-detail view needs in one call: the driver's standing (rank via the
     * Redis leaderboard), window aggregates, recent trips and day-by-day score trend.
     */
    @GetMapping("/drivers/{id}/detail")
    public DriverDetailDto driverDetail(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                        @RequestParam(defaultValue = "30") int days) {
        int d = Math.clamp(days, 1, 90);
        long tenant = CurrentUser.tenantId(jwt);
        DriverScoreDto s = reporting.findDriverSummary(tenant, id, d);
        DriverRankDto r = leaderboard.rank(tenant, d, id);
        return new DriverDetailDto(
                id,
                s == null ? null : s.name(),
                r == null ? null : r.rank(),
                r == null ? null : r.total(),
                s == null ? null : s.score(),
                s == null ? null : s.distanceKm(),
                s == null ? null : s.violationCount(),
                s == null ? 0 : s.tripsScored(),
                reporting.findDriverTrips(tenant, id, 10),
                reporting.findDriverScoreDaily(tenant, id, d));
    }

    @GetMapping("/trips/{id}/route")
    public List<TripPointDto> tripRoute(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return reporting.findTripRoute(id, CurrentUser.tenantId(jwt));
    }
}
