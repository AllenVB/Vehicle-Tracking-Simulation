package com.fleet.vts.gateway.repository;

import com.fleet.vts.gateway.web.dto.DashboardSummaryDto;
import com.fleet.vts.gateway.web.dto.DriverScoreDto;
import com.fleet.vts.gateway.web.dto.FuelStationDto;
import com.fleet.vts.gateway.web.dto.GeofenceDto;
import com.fleet.vts.gateway.web.dto.TelemetryBucketDto;
import com.fleet.vts.gateway.web.dto.TripPointDto;
import com.fleet.vts.gateway.web.dto.TripSummaryDto;
import com.fleet.vts.gateway.web.dto.VehicleCategoryDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The read side behind the dashboard and the map: fleet counters, telemetry history,
 * geofences, fuel stations, driver scores and trip routes.
 *
 * <p>JdbcTemplate rather than JPA throughout, because every query here reaches for
 * something JPA does not model — Timescale continuous aggregates
 * ({@code telemetry_1min}), PostGIS unwrapping ({@code ST_Y}, {@code ST_AsGeoJSON}) and
 * grouped aggregates. Rows map straight to DTOs; none of these shapes is an entity.
 *
 * <p>Every query is tenant-scoped. {@link #findTripRoute} scopes via a join on {@code trip}
 * rather than a column of its own, since {@code trip_point} has no tenant column.
 */
@Repository
public class ReportingQueryRepository {

    private final JdbcTemplate jdbc;

    public ReportingQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public DashboardSummaryDto findDashboardSummary(long tenantId) {
        return jdbc.queryForObject("""
                SELECT
                  (SELECT count(*) FROM vehicle WHERE tenant_id = ?) AS vehicles,
                  (SELECT count(*) FROM vehicle WHERE tenant_id = ? AND status = 'ACTIVE') AS active_vehicles,
                  (SELECT count(*) FROM driver WHERE tenant_id = ?) AS drivers,
                  (SELECT count(*) FROM violation WHERE tenant_id = ? AND occurred_at >= now() - INTERVAL '24 hours') AS violations_24h,
                  (SELECT count(*) FROM trip WHERE tenant_id = ? AND status = 'ONGOING') AS ongoing_trips
                """,
                (rs, n) -> new DashboardSummaryDto(
                        rs.getLong("vehicles"),
                        rs.getLong("active_vehicles"),
                        rs.getLong("drivers"),
                        rs.getLong("violations_24h"),
                        rs.getLong("ongoing_trips")),
                tenantId, tenantId, tenantId, tenantId, tenantId);
    }

    /** Served from the 1-minute continuous aggregate, never the raw hypertable. */
    public List<TelemetryBucketDto> findTelemetryBuckets(long tenantId, long vehicleId,
                                                         Instant from, Instant to) {
        return jdbc.query("""
                SELECT bucket, avg_speed_kmh, max_speed_kmh, min_battery, min_fuel_pct, sample_count
                FROM telemetry_1min
                WHERE tenant_id = ? AND vehicle_id = ? AND bucket >= ? AND bucket < ?
                ORDER BY bucket
                """,
                (rs, n) -> new TelemetryBucketDto(
                        rs.getObject("bucket", OffsetDateTime.class).toInstant(),
                        rs.getBigDecimal("avg_speed_kmh"),
                        rs.getObject("max_speed_kmh", Integer.class),
                        rs.getObject("min_battery", Integer.class),
                        rs.getObject("min_fuel_pct", Integer.class),
                        rs.getLong("sample_count")),
                tenantId, vehicleId, atUtc(from), atUtc(to));
    }

    /**
     * Active geofences as GeoJSON, so the map can show the zones it keeps raising
     * enter/exit events for. Without this the alerts are unexplainable: you see "entered a
     * restricted zone" but no zone.
     */
    public List<GeofenceDto> findActiveGeofences(long tenantId) {
        return jdbc.query("""
                SELECT id, name, kind, ST_AsGeoJSON(area::geometry) AS geojson
                FROM geofence
                WHERE tenant_id = ? AND active = true
                """,
                (rs, n) -> new GeofenceDto(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("kind"),
                        rs.getString("geojson")),
                tenantId);
    }

    public List<FuelStationDto> findFuelStations(long tenantId) {
        return jdbc.query("""
                SELECT name, brand,
                       ST_Y(location::geometry) AS lat, ST_X(location::geometry) AS lon
                FROM fuel_station WHERE tenant_id = ?
                """,
                (rs, n) -> new FuelStationDto(
                        rs.getString("name"),
                        rs.getString("brand"),
                        rs.getDouble("lat"),
                        rs.getDouble("lon")),
                tenantId);
    }

    /** Best drivers over the window, by average daily score. */
    public List<DriverScoreDto> findDriverScores(long tenantId, int days, int limit) {
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
                (rs, n) -> new DriverScoreDto(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getBigDecimal("avg_score"),
                        rs.getBigDecimal("distance_km"),
                        rs.getObject("violation_count", Long.class),
                        rs.getLong("days_scored")),
                tenantId, days, limit);
    }

    public List<TripSummaryDto> findVehicleTrips(long tenantId, long vehicleId, int limit) {
        return jdbc.query("""
                SELECT id, started_at, ended_at, distance_km, status
                FROM trip
                WHERE tenant_id = ? AND vehicle_id = ?
                ORDER BY started_at DESC
                LIMIT ?
                """,
                (rs, n) -> {
                    OffsetDateTime ended = rs.getObject("ended_at", OffsetDateTime.class);
                    return new TripSummaryDto(
                            rs.getLong("id"),
                            rs.getObject("started_at", OffsetDateTime.class).toInstant(),
                            ended == null ? null : ended.toInstant(),
                            rs.getBigDecimal("distance_km"),
                            rs.getString("status"));
                },
                tenantId, vehicleId, limit);
    }

    /**
     * The fleet taxonomy: every category with its types, in display order, each type
     * carrying how many of the tenant's vehicles are of that type.
     *
     * <p>Categories are returned even when empty, so an operator can see that a maritime
     * fleet is expressible before one exists. The count is a LEFT JOIN for the same reason:
     * a type with no vehicles is still a type.
     */
    public List<VehicleCategoryDto> findVehicleTaxonomy(long tenantId) {
        Map<String, List<VehicleCategoryDto.VehicleTypeDto>> typesByCategory = new LinkedHashMap<>();
        jdbc.query("""
                SELECT t.category, t.code, t.label, count(v.id) AS vehicle_count
                FROM vehicle_type t
                LEFT JOIN vehicle v ON v.type = t.code AND v.tenant_id = ?
                GROUP BY t.category, t.code, t.label, t.sort_order
                ORDER BY t.sort_order
                """,
                rs -> {
                    typesByCategory
                            .computeIfAbsent(rs.getString("category"), k -> new ArrayList<>())
                            .add(new VehicleCategoryDto.VehicleTypeDto(
                                    rs.getString("code"),
                                    rs.getString("label"),
                                    rs.getLong("vehicle_count")));
                },
                tenantId);

        return jdbc.query("SELECT code, label FROM vehicle_category ORDER BY sort_order",
                (rs, n) -> new VehicleCategoryDto(
                        rs.getString("code"),
                        rs.getString("label"),
                        typesByCategory.getOrDefault(rs.getString("code"), List.of())));
    }

    /** The trip's breadcrumb. Tenant ownership is enforced by the join on {@code trip}. */
    public List<TripPointDto> findTripRoute(long tripId, long tenantId) {
        return jdbc.query("""
                SELECT tp.seq, tp.ts, ST_Y(tp.location::geometry) AS lat, ST_X(tp.location::geometry) AS lon,
                       tp.speed_kmh
                FROM trip_point tp JOIN trip t ON t.id = tp.trip_id
                WHERE tp.trip_id = ? AND t.tenant_id = ?
                ORDER BY tp.seq
                """,
                (rs, n) -> new TripPointDto(
                        rs.getInt("seq"),
                        rs.getDouble("lat"),
                        rs.getDouble("lon"),
                        rs.getObject("speed_kmh", Integer.class)),
                tripId, tenantId);
    }

    private static OffsetDateTime atUtc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
