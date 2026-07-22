package com.fleet.vts.gateway.web;

import com.fleet.vts.gateway.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Drawing and removing restricted zones.
 *
 * <p>Reading geofences has always worked; creating one meant writing a migration. That made
 * the zone list a deployment artefact rather than an operational tool — an operator who
 * needed a zone around today's roadworks had to file a code change.
 *
 * <p>The polygon arrives as a ring of {@code [lat, lon]} pairs and is turned into a PostGIS
 * geography here rather than in the browser. Two reasons: the ring has to be closed and wound
 * correctly for {@code ST_MakePolygon} to accept it, and letting a client post arbitrary WKT
 * into a query is how you get an injection.
 */
@RestController
@RequestMapping("/api/v1/geofences")
public class GeofenceController {

    /** A polygon this small is a mis-click; this large is not a zone, it is a region. */
    private static final int MIN_VERTICES = 3;
    private static final int MAX_VERTICES = 200;

    private static final Set<String> KINDS = Set.of("EXCLUSION", "INCLUSION");

    private final JdbcTemplate jdbc;

    public GeofenceController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code points} is a ring of [lat, lon] pairs, open or closed — both are accepted. */
    public record GeofenceRequest(String name, String kind, List<List<Double>> points) {
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'FLEET_MANAGER')")
    public ResponseEntity<Map<String, Object>> create(@AuthenticationPrincipal Jwt jwt,
                                                      @RequestBody GeofenceRequest request) {
        long tenant = CurrentUser.tenantId(jwt);

        String name = request.name() == null || request.name().isBlank()
                ? "Yeni bölge" : request.name().trim();
        if (name.length() > 120) {
            name = name.substring(0, 120);
        }
        String kind = request.kind() == null ? "EXCLUSION" : request.kind().trim().toUpperCase();
        if (!KINDS.contains(kind)) {
            return ResponseEntity.badRequest().body(Map.of("error", "UNKNOWN_KIND", "kind", kind));
        }

        List<List<Double>> points = request.points();
        if (points == null || points.size() < MIN_VERTICES || points.size() > MAX_VERTICES) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "BAD_POLYGON",
                    "detail", "Köşe sayısı " + MIN_VERTICES + ".." + MAX_VERTICES + " olmalı"));
        }
        for (List<Double> p : points) {
            if (p == null || p.size() != 2 || p.get(0) == null || p.get(1) == null
                    || Math.abs(p.get(0)) > 90 || Math.abs(p.get(1)) > 180) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "BAD_POINT", "detail", "Her köşe [lat, lon] olmalı"));
            }
        }

        String wkt = toPolygonWkt(points);
        Long id = jdbc.queryForObject("""
                INSERT INTO geofence (tenant_id, name, kind, area, active)
                VALUES (?, ?, ?, ST_MakeValid(ST_GeomFromText(?, 4326))::geography, true)
                RETURNING id
                """, Long.class, tenant, name, kind, wkt);

        return ResponseEntity.status(201).body(Map.of("id", id, "name", name, "kind", kind));
    }

    /**
     * Deactivates rather than deletes.
     *
     * <p>{@code geofence_event} rows reference the zone that raised them. Removing the row
     * would either fail on the foreign key or, worse, orphan a history of events nobody can
     * explain any more. A zone that is over is inactive, not undone.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'FLEET_MANAGER')")
    public ResponseEntity<Void> deactivate(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        int updated = jdbc.update(
                "UPDATE geofence SET active = false, updated_at = now() WHERE id = ? AND tenant_id = ?",
                id, CurrentUser.tenantId(jwt));
        return updated == 0 ? ResponseEntity.notFound().build() : ResponseEntity.noContent().build();
    }

    /**
     * Builds {@code POLYGON((lon lat, ...))} — WKT is longitude first, which is the opposite
     * of the order the map hands over and a reliable way to end up with a zone in the sea.
     *
     * <p>Coordinates are formatted, never concatenated from user strings, so nothing the
     * client sends can reach the query as SQL.
     */
    private static String toPolygonWkt(List<List<Double>> points) {
        StringJoiner ring = new StringJoiner(", ", "POLYGON((", "))");
        for (List<Double> p : points) {
            ring.add(coordinate(p));
        }
        // A ring must close. Clients that already closed it are not made to close it twice.
        List<Double> first = points.get(0);
        List<Double> last = points.get(points.size() - 1);
        if (!first.equals(last)) {
            ring.add(coordinate(first));
        }
        return ring.toString();
    }

    private static String coordinate(List<Double> latLon) {
        return String.format(java.util.Locale.ROOT, "%.7f %.7f", latLon.get(1), latLon.get(0));
    }
}
