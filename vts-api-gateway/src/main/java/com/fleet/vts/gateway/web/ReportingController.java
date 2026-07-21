package com.fleet.vts.gateway.web;

import com.fleet.vts.gateway.repository.ReportingQueryRepository;
import com.fleet.vts.gateway.security.CurrentUser;
import com.fleet.vts.gateway.web.dto.DashboardSummaryDto;
import com.fleet.vts.gateway.web.dto.DriverScoreDto;
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

    public ReportingController(ReportingQueryRepository reporting) {
        this.reporting = reporting;
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

    /** Driver scoreboard: best drivers over the window, by average daily score. */
    @GetMapping("/drivers/scores")
    public List<DriverScoreDto> driverScores(@AuthenticationPrincipal Jwt jwt,
                                             @RequestParam(defaultValue = "30") int days,
                                             @RequestParam(defaultValue = "20") int limit) {
        int capped = Math.clamp(limit, 1, MAX_DRIVER_SCORES);
        return reporting.findDriverScores(CurrentUser.tenantId(jwt), days, capped);
    }

    @GetMapping("/vehicles/{id}/trips")
    public List<TripSummaryDto> vehicleTrips(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                             @RequestParam(defaultValue = "20") int limit) {
        return reporting.findVehicleTrips(CurrentUser.tenantId(jwt), id, limit);
    }

    @GetMapping("/trips/{id}/route")
    public List<TripPointDto> tripRoute(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return reporting.findTripRoute(id, CurrentUser.tenantId(jwt));
    }
}
