package com.fleet.vts.ingestion.adapter.out.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleet.vts.ingestion.domain.VehicleRef;
import com.fleet.vts.ingestion.port.out.VehicleLookupPort;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Three-tier IMEI -> vehicle resolution: an in-process Caffeine cache, then a
 * shared Redis cache, then the database. Warm tiers are back-filled on a miss so
 * the hot path stays off the database. Fleet mapping changes rarely, so short
 * TTLs are acceptable.
 */
@Component
public class CachedVehicleLookupAdapter implements VehicleLookupPort {

    private static final Logger log = LoggerFactory.getLogger(CachedVehicleLookupAdapter.class);
    private static final String REDIS_PREFIX = "vts:imei:";
    private static final Duration REDIS_TTL = Duration.ofMinutes(10);
    private static final String SQL = """
            SELECT v.id AS vehicle_id, v.tenant_id AS tenant_id, d.id AS device_id
            FROM device d
            JOIN vehicle v ON v.id = d.vehicle_id
            WHERE d.imei = ?
            """;

    private final Cache<String, VehicleRef> localCache;
    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public CachedVehicleLookupAdapter(StringRedisTemplate redis, JdbcTemplate jdbc,
                                      ObjectMapper objectMapper) {
        this.redis = redis;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.localCache = Caffeine.newBuilder()
                .maximumSize(50_000)
                .expireAfterWrite(Duration.ofMinutes(5))
                .build();
    }

    @Override
    public Optional<VehicleRef> findByImei(String imei) {
        VehicleRef local = localCache.getIfPresent(imei);
        if (local != null) {
            return Optional.of(local);
        }
        VehicleRef fromRedis = readRedis(imei);
        if (fromRedis != null) {
            localCache.put(imei, fromRedis);
            return Optional.of(fromRedis);
        }
        VehicleRef fromDb = readDb(imei);
        if (fromDb != null) {
            writeRedis(imei, fromDb);
            localCache.put(imei, fromDb);
            return Optional.of(fromDb);
        }
        return Optional.empty();
    }

    private VehicleRef readRedis(String imei) {
        try {
            String json = redis.opsForValue().get(REDIS_PREFIX + imei);
            return json == null ? null : objectMapper.readValue(json, VehicleRef.class);
        } catch (Exception e) {
            // Redis is a cache, not the source of truth: never fail the lookup on it.
            log.warn("Redis lookup failed for imei {}: {}", imei, e.getMessage());
            return null;
        }
    }

    private void writeRedis(String imei, VehicleRef ref) {
        try {
            redis.opsForValue().set(REDIS_PREFIX + imei, objectMapper.writeValueAsString(ref), REDIS_TTL);
        } catch (Exception e) {
            log.warn("Redis cache write failed for imei {}: {}", imei, e.getMessage());
        }
    }

    private VehicleRef readDb(String imei) {
        try {
            return jdbc.queryForObject(SQL, (rs, n) -> new VehicleRef(
                    rs.getLong("vehicle_id"),
                    rs.getLong("tenant_id"),
                    rs.getLong("device_id")), imei);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }
}
