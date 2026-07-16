package com.fleet.vts.gateway.web;

import com.fleet.vts.gateway.security.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dashboard summary plus read-optimised time-series endpoints. Telemetry history
 * is served from the continuous aggregate ({@code telemetry_1min}), never the
 * raw hypertable; trip routes are rebuilt from {@code trip_point}.
 */
@RestController
@RequestMapping("/api/v1")
public class ReportingController {

    private final JdbcTemplate jdbc;

    public ReportingController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/dashboard/summary")
    public Map<String, Object> summary(@AuthenticationPrincipal Jwt jwt) {
        long tenant = CurrentUser.tenantId(jwt);
        return jdbc.queryForObject("""
                SELECT
                  (SELECT count(*) FROM vehicle WHERE tenant_id = ?) AS vehicles,
                  (SELECT count(*) FROM vehicle WHERE tenant_id = ? AND status = 'ACTIVE') AS active_vehicles,
                  (SELECT count(*) FROM driver WHERE tenant_id = ?) AS drivers,
                  (SELECT count(*) FROM violation WHERE tenant_id = ? AND occurred_at >= now() - INTERVAL '24 hours') AS violations_24h,
                  (SELECT count(*) FROM trip WHERE tenant_id = ? AND status = 'ONGOING') AS ongoing_trips
                """,
                (rs, n) -> Map.of(
                        "vehicles", rs.getLong("vehicles"),
                        "activeVehicles", rs.getLong("active_vehicles"),
                        "drivers", rs.getLong("drivers"),
                        "violations24h", rs.getLong("violations_24h"),
                        "ongoingTrips", rs.getLong("ongoing_trips")),
                tenant, tenant, tenant, tenant, tenant);
    }

    @GetMapping("/vehicles/{id}/telemetry")
    public List<Map<String, Object>> telemetry(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                               @RequestParam Instant from, @RequestParam Instant to) {
        return jdbc.query("""
                SELECT bucket, avg_speed_kmh, max_speed_kmh, min_battery, min_fuel_pct, sample_count
                FROM telemetry_1min
                WHERE tenant_id = ? AND vehicle_id = ? AND bucket >= ? AND bucket < ?
                ORDER BY bucket
                """,
                (rs, n) -> Map.<String, Object>of(
                        "bucket", rs.getObject("bucket", OffsetDateTime.class).toInstant().toString(),
                        "avgSpeedKmh", rs.getBigDecimal("avg_speed_kmh"),
                        "maxSpeedKmh", rs.getObject("max_speed_kmh"),
                        "minBattery", rs.getObject("min_battery"),
                        "minFuelPct", rs.getObject("min_fuel_pct"),
                        "sampleCount", rs.getLong("sample_count")),
                CurrentUser.tenantId(jwt), id,
                OffsetDateTime.ofInstant(from, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(to, ZoneOffset.UTC));
    }

    /**
     * Active geofences as GeoJSON, so the map can actually show the zones it keeps
     * raising enter/exit events for. Without this the alerts are unexplainable: you see
     * "entered a restricted zone" but no zone.
     */
    @GetMapping("/geofences")
    public List<Map<String, Object>> geofences(@AuthenticationPrincipal Jwt jwt) {
        return jdbc.query("""
                SELECT id, name, kind, ST_AsGeoJSON(area::geometry) AS geojson
                FROM geofence
                WHERE tenant_id = ? AND active = true
                """,
                (rs, n) -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("name", rs.getString("name"));
                    m.put("kind", rs.getString("kind"));   // EXCLUSION / INCLUSION
                    m.put("geojson", rs.getString("geojson"));
                    return m;
                },
                CurrentUser.tenantId(jwt));
    }

    /** Fuel stations (for the map and the nearest-station distance shown on select). */
    @GetMapping("/fuel-stations")
    public List<Map<String, Object>> fuelStations(@AuthenticationPrincipal Jwt jwt) {
        return jdbc.query("""
                SELECT name, brand,
                       ST_Y(location::geometry) AS lat, ST_X(location::geometry) AS lon
                FROM fuel_station WHERE tenant_id = ?
                """,
                (rs, n) -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("name", rs.getString("name"));
                    m.put("brand", rs.getString("brand"));
                    m.put("lat", rs.getDouble("lat"));
                    m.put("lon", rs.getDouble("lon"));
                    return m;
                },
                CurrentUser.tenantId(jwt));
    }

    /** Driver scoreboard: best drivers over the window, by average daily score. */
    @GetMapping("/drivers/scores")
    public List<Map<String, Object>> driverScores(@AuthenticationPrincipal Jwt jwt,
                                                  @RequestParam(defaultValue = "30") int days,
                                                  @RequestParam(defaultValue = "20") int limit) {
        return jdbc.query("""
                SELECT d.id,
                       d.first_name || ' ' || d.last_name AS name,
                       round(avg(s.score), 1)             AS avg_score,
                       round(sum(s.distance_km), 0)       AS distance_km,
                       sum(s.violation_count)             AS violation_count,
                       count(*)                           AS days_scored
                FROM driver_score_daily s
                JOIN driver d ON d.id = s.driver_id
                WHERE s.tenant_id = ? AND s.score_date >= current_date - ?::int
                GROUP BY d.id, name
                ORDER BY avg_score DESC NULLS LAST
                LIMIT ?
                """,
                (rs, n) -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("driverId", rs.getLong("id"));
                    m.put("name", rs.getString("name"));
                    m.put("score", rs.getBigDecimal("avg_score"));
                    m.put("distanceKm", rs.getBigDecimal("distance_km"));
                    m.put("violationCount", rs.getLong("violation_count"));
                    m.put("daysScored", rs.getLong("days_scored"));
                    return m;
                },
                CurrentUser.tenantId(jwt), days, Math.min(Math.max(limit, 1), 100));
    }

    @GetMapping("/vehicles/{id}/trips")
    public List<Map<String, Object>> vehicleTrips(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                                  @RequestParam(defaultValue = "20") int limit) {
        return jdbc.query("""
                SELECT id, started_at, ended_at, distance_km, status
                FROM trip
                WHERE tenant_id = ? AND vehicle_id = ?
                ORDER BY started_at DESC
                LIMIT ?
                """,
                (rs, n) -> {
                    OffsetDateTime ended = rs.getObject("ended_at", OffsetDateTime.class);
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("startedAt", rs.getObject("started_at", OffsetDateTime.class).toInstant().toString());
                    m.put("endedAt", ended == null ? null : ended.toInstant().toString());
                    m.put("distanceKm", rs.getObject("distance_km"));
                    m.put("status", rs.getString("status"));
                    return m;
                },
                CurrentUser.tenantId(jwt), id, limit);
    }

    @GetMapping("/trips/{id}/route")
    public List<Map<String, Object>> tripRoute(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        // tenant ownership enforced by joining trip
        return jdbc.query("""
                SELECT tp.seq, tp.ts, ST_Y(tp.location::geometry) AS lat, ST_X(tp.location::geometry) AS lon,
                       tp.speed_kmh
                FROM trip_point tp JOIN trip t ON t.id = tp.trip_id
                WHERE tp.trip_id = ? AND t.tenant_id = ?
                ORDER BY tp.seq
                """,
                (rs, n) -> Map.<String, Object>of(
                        "seq", rs.getInt("seq"),
                        "lat", rs.getDouble("lat"),
                        "lon", rs.getDouble("lon"),
                        "speedKmh", rs.getObject("speed_kmh")),
                id, CurrentUser.tenantId(jwt));
    }
}
