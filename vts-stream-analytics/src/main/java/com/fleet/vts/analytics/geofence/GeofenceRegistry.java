package com.fleet.vts.analytics.geofence;

import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Autowired;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.io.WKTReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * In-memory registry of active geofence polygons for point-in-polygon tests (JTS). Stands in
 * for the GlobalKTable design until geofences are streamed via CDC (phase 2).
 *
 * <p>Reloaded on a timer, not only at startup. Zones used to arrive by migration, so "loaded
 * once" was the same as "always current". Now an operator draws one on the map, and a registry
 * that only reads at boot would leave the rule engine enforcing yesterday's zones until the
 * next deploy — with no error anywhere to say so.
 */
@Component
public class GeofenceRegistry {

    /** An active geofence with its polygon geometry. */
    public record Area(Long id, String name, Long tenantId, Polygon polygon) {
    }

    private static final Logger log = LoggerFactory.getLogger(GeofenceRegistry.class);
    private static final GeometryFactory GEOMETRY = new GeometryFactory();

    private final JdbcTemplate jdbc;
    private volatile List<Area> areas;

    @Autowired
    public GeofenceRegistry(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.areas = List.of();
    }

    /** Test constructor with a preloaded set of areas (no database). */
    public GeofenceRegistry(List<Area> areas) {
        this.jdbc = null;
        this.areas = List.copyOf(areas);
    }

    @PostConstruct
    @Scheduled(fixedDelay = 60_000)
    public void load() {
        if (jdbc == null) {
            return;
        }
        WKTReader reader = new WKTReader(GEOMETRY);
        List<Area> loaded = new ArrayList<>();
        jdbc.query("SELECT id, name, tenant_id, ST_AsText(area::geometry) AS wkt "
                + "FROM geofence WHERE active = true", rs -> {
            try {
                Polygon polygon = (Polygon) reader.read(rs.getString("wkt"));
                loaded.add(new Area(rs.getLong("id"), rs.getString("name"),
                        rs.getLong("tenant_id"), polygon));
            } catch (Exception e) {
                log.warn("Skipping geofence {} with unparseable geometry: {}",
                        rs.getLong("id"), e.getMessage());
            }
        });
        // Swapped wholesale rather than mutated: the rule reads this list on the stream thread
        // and a half-updated list would be a zone that briefly does not exist.
        List<Area> previous = this.areas;
        this.areas = List.copyOf(loaded);
        if (previous.size() != areas.size()) {
            log.info("Active geofences: {} (was {})", areas.size(), previous.size());
        }
    }

    public java.util.Optional<Area> find(Long id) {
        return areas.stream().filter(a -> a.id().equals(id)).findFirst();
    }

    /** The geofences whose polygon currently contains the point (lon/lat WGS84). */
    public List<Area> areasContaining(double lat, double lon) {
        Point point = GEOMETRY.createPoint(new Coordinate(lon, lat));
        List<Area> result = new ArrayList<>();
        for (Area area : areas) {
            if (area.polygon().covers(point)) {
                result.add(area);
            }
        }
        return result;
    }
}
