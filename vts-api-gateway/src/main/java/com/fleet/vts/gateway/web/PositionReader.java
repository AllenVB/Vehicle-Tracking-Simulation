package com.fleet.vts.gateway.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Bir aracın en güncel konumunu tek yerden okur: önce Redis (gerçek zamanlı,
 * {@code vts:pos:{vehicleId}}), yoksa {@code vehicle_last_position} DB tablosu (soğuk
 * başlangıç). Görev ETA'sı ve müşteri paylaşım linki aynı kaynağı kullansın diye ortak.
 */
@Component
public class PositionReader {

    private static final String POS_PREFIX = "vts:pos:";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public PositionReader(StringRedisTemplate redis, JdbcTemplate jdbc, ObjectMapper json) {
        this.redis = redis;
        this.jdbc = jdbc;
        this.json = json;
    }

    /** lat/lon null ise konum bilinmiyor demektir. */
    public record Pos(Double lat, Double lon, Double speedKmh) {
        public boolean known() {
            return lat != null && lon != null;
        }
    }

    public Pos read(long vehicleId) {
        String cached = redis.opsForValue().get(POS_PREFIX + vehicleId);
        if (cached != null) {
            try {
                Map<String, Object> m = json.readValue(cached, MAP_TYPE);
                return new Pos(num(m.get("lat")), num(m.get("lon")), num(m.get("speedKmh")));
            } catch (Exception ignored) {
                // düşer, DB fallback'e
            }
        }
        try {
            Map<String, Object> db = jdbc.queryForMap("""
                    SELECT ST_Y(location::geometry) AS lat,
                           ST_X(location::geometry) AS lon,
                           speed_kmh
                    FROM vehicle_last_position
                    WHERE vehicle_id = ?
                    """, vehicleId);
            return new Pos(num(db.get("lat")), num(db.get("lon")), num(db.get("speed_kmh")));
        } catch (Exception e) {
            return new Pos(null, null, null);
        }
    }

    private static Double num(Object o) {
        return (o instanceof Number n) ? n.doubleValue() : null;
    }
}
