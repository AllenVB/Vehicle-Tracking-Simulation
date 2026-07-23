package com.fleet.vts.gateway.live;

import com.fleet.vts.gateway.security.CurrentUser;
import com.fleet.vts.gateway.web.dto.NearestVehicleDto;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.data.redis.domain.geo.GeoShape;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * Nearest-vehicle dispatch, answered from the Redis geospatial index that {@code PositionCache}
 * maintains ({@code GEOADD} per tick into {@code vts:geo:<tenant>}). A {@code GEOSEARCH} is an
 * O(log n) lookup straight from memory, so the operator can point at a spot on the map and get
 * the closest vehicles instantly — no PostGIS scan of the live table, and the coordinates come
 * back with the result so no second position lookup is needed.
 */
@RestController
@RequestMapping("/api/v1/dispatch")
public class DispatchController {

    private static final String GEO_PREFIX = "vts:geo:";
    private static final int MAX_RESULTS = 20;
    private static final double MAX_RADIUS_KM = 1000;

    private final StringRedisTemplate redis;

    public DispatchController(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * The {@code limit} vehicles nearest to {@code (lat, lon)} within {@code radiusKm}, closest
     * first, each with its straight-line distance. Empty when the index is cold or nothing is in
     * range — the operator sees "yakında araç yok" rather than an error.
     */
    @GetMapping("/nearest")
    public List<NearestVehicleDto> nearest(@AuthenticationPrincipal Jwt jwt,
                                           @RequestParam double lat,
                                           @RequestParam double lon,
                                           @RequestParam(defaultValue = "5") int limit,
                                           @RequestParam(defaultValue = "250") double radiusKm) {
        int cappedLimit = Math.clamp(limit, 1, MAX_RESULTS);
        double cappedRadius = Math.min(Math.max(radiusKm, 1), MAX_RADIUS_KM);
        String key = GEO_PREFIX + CurrentUser.tenantId(jwt);

        // x = longitude, y = latitude (Redis GEO order).
        RedisGeoCommands.GeoSearchCommandArgs args = RedisGeoCommands.GeoSearchCommandArgs
                .newGeoSearchArgs().includeDistance().includeCoordinates().sortAscending().limit(cappedLimit);

        GeoResults<RedisGeoCommands.GeoLocation<String>> results = redis.opsForGeo().search(
                key,
                GeoReference.fromCoordinate(new Point(lon, lat)),
                GeoShape.byRadius(new Distance(cappedRadius, Metrics.KILOMETERS)),
                args);

        List<NearestVehicleDto> out = new ArrayList<>();
        if (results == null) {
            return out;
        }
        for (GeoResult<RedisGeoCommands.GeoLocation<String>> r : results) {
            RedisGeoCommands.GeoLocation<String> loc = r.getContent();
            long vehicleId;
            try {
                vehicleId = Long.parseLong(loc.getName());
            } catch (NumberFormatException ex) {
                continue;   // non-numeric member should not exist, but never let it break the list
            }
            Point p = loc.getPoint();
            double distanceKm = r.getDistance() == null ? 0 : r.getDistance().getValue();
            out.add(new NearestVehicleDto(vehicleId,
                    Math.round(distanceKm * 10) / 10.0,
                    p == null ? null : p.getY(),
                    p == null ? null : p.getX()));
        }
        return out;
    }
}
