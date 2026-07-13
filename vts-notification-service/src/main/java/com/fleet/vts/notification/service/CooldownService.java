package com.fleet.vts.notification.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Per-(vehicle, rule) notification rate limiting via Redis SETNX + TTL. The
 * first call within the cooldown window acquires the lock; later calls are
 * suppressed until it expires. If Redis is unavailable, notifications are
 * allowed (fail-open) rather than lost.
 */
@Service
public class CooldownService {

    private static final Logger log = LoggerFactory.getLogger(CooldownService.class);

    private final StringRedisTemplate redis;

    public CooldownService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean tryAcquire(Long tenantId, Long vehicleId, String ruleCode, int cooldownSeconds) {
        int ttl = cooldownSeconds > 0 ? cooldownSeconds : 300;
        String key = "vts:notif:" + tenantId + ":" + vehicleId + ":" + ruleCode;
        try {
            Boolean acquired = redis.opsForValue().setIfAbsent(key, "1", Duration.ofSeconds(ttl));
            return Boolean.TRUE.equals(acquired);
        } catch (Exception e) {
            log.warn("Redis cooldown check failed for {}, allowing: {}", key, e.getMessage());
            return true;
        }
    }
}
