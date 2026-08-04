package com.fleet.vts.gateway.track;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Concurrent single-session control for the driver app, backed by Redis TTL.
 *
 * <p>The requirement is a lease, not a permanent bind: a vehicle may be driven by exactly ONE
 * phone at a time, but a different phone may take it over later. Two keys express it:
 * <ul>
 *   <li>{@code driver:sess:{vehicleId}} = deviceId — the current holder, TTL {@link #HOLD}.
 *       Every telemetry post refreshes it; when the phone goes quiet the lease lapses on its own
 *       and the vehicle frees itself, so the next device can sign in without an admin unlock.</li>
 *   <li>{@code driver:tok:{token}} = "{vehicleId}:{deviceId}" — maps the opaque session token the
 *       phone echoes on every post back to its identity, TTL {@link #TOKEN}.</li>
 * </ul>
 * A login is refused (conflict) only while another device still holds a live lease.
 */
@Service
public class DriverSessionService {

    /** Holder lease: how long a vehicle stays "taken" after the last telemetry post. */
    static final Duration HOLD = Duration.ofSeconds(90);
    /** Session-token lifetime: the same device may resume within this window. */
    static final Duration TOKEN = Duration.ofHours(12);

    private final StringRedisTemplate redis;

    public DriverSessionService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public record Identity(long vehicleId, String deviceId) {
    }

    private static String sessKey(long vehicleId) {
        return "driver:sess:" + vehicleId;
    }

    private static String tokKey(String token) {
        return "driver:tok:" + token;
    }

    /**
     * Try to take the vehicle for this device. Returns a session token on success, or empty when
     * another device currently holds a live lease (concurrent use blocked).
     */
    public Optional<String> tryLogin(long vehicleId, String deviceId) {
        String holder = redis.opsForValue().get(sessKey(vehicleId));
        if (holder != null && !holder.equals(deviceId)) {
            return Optional.empty();   // someone else is driving this vehicle right now
        }
        String token = UUID.randomUUID().toString();
        redis.opsForValue().set(sessKey(vehicleId), deviceId, HOLD);
        redis.opsForValue().set(tokKey(token), vehicleId + ":" + deviceId, TOKEN);
        return Optional.of(token);
    }

    public Optional<Identity> identify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String v = redis.opsForValue().get(tokKey(token));
        if (v == null) {
            return Optional.empty();
        }
        int i = v.indexOf(':');
        if (i < 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Identity(Long.parseLong(v.substring(0, i)), v.substring(i + 1)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * Refresh the lease for a token's device. Returns false when the token is unknown/expired, or
     * the vehicle has since been taken over by another device — the caller should stop and warn.
     */
    public boolean refresh(String token) {
        Optional<Identity> id = identify(token);
        if (id.isEmpty()) {
            return false;
        }
        long vehicleId = id.get().vehicleId();
        String deviceId = id.get().deviceId();
        String holder = redis.opsForValue().get(sessKey(vehicleId));
        if (holder != null && !holder.equals(deviceId)) {
            return false;              // taken over by another device
        }
        // Reclaim if the lease had lapsed, refresh if still held — both are this device's right.
        redis.opsForValue().set(sessKey(vehicleId), deviceId, HOLD);
        return true;
    }

    public void logout(String token) {
        identify(token).ifPresent(id -> {
            String holder = redis.opsForValue().get(sessKey(id.vehicleId()));
            if (id.deviceId().equals(holder)) {
                redis.delete(sessKey(id.vehicleId()));
            }
        });
        if (token != null && !token.isBlank()) {
            redis.delete(tokKey(token));
        }
    }

    /** Admin force-logout: free a vehicle regardless of who holds it. */
    public void evict(long vehicleId) {
        redis.delete(sessKey(vehicleId));
    }
}
