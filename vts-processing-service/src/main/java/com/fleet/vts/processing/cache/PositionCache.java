package com.fleet.vts.processing.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleet.vts.common.event.TelemetryEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Writes the latest position per vehicle to Redis in a single pipeline (TTL
 * 5 min). Redis is a cache, not the source of truth (that is
 * {@code vehicle_last_position}), so failures are logged and swallowed.
 */
@Component
public class PositionCache {

    private static final Logger log = LoggerFactory.getLogger(PositionCache.class);
    private static final String PREFIX = "vts:pos:";
    private static final Duration TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public PositionCache(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public void cacheLatest(List<TelemetryEvent> latestPerVehicle) {
        if (latestPerVehicle.isEmpty()) {
            return;
        }
        try {
            redis.executePipelined(new SessionCallback<Object>() {
                @Override
                @SuppressWarnings("unchecked")
                public <K, V> Object execute(RedisOperations<K, V> operations) {
                    RedisOperations<String, String> ops = (RedisOperations<String, String>) operations;
                    for (TelemetryEvent e : latestPerVehicle) {
                        ops.opsForValue().set(PREFIX + e.vehicleId(), toJson(e), TTL);
                    }
                    return null;
                }
            });
        } catch (Exception ex) {
            log.warn("Redis position cache pipeline failed: {}", ex.getMessage());
        }
    }

    private String toJson(TelemetryEvent e) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "lat", e.lat(),
                    "lon", e.lon(),
                    "speedKmh", e.speedKmh(),
                    "heading", e.heading(),
                    "ts", e.ts().toString()));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize position", ex);
        }
    }
}
