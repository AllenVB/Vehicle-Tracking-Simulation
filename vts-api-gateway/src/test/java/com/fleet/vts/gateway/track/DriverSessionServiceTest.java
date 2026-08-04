package com.fleet.vts.gateway.track;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The single-driver lease semantics, verified against an in-memory stand-in for Redis (no Docker):
 * one device holds a vehicle at a time, a second device is blocked while the lease is live, and a
 * different device may take over only once the lease has lapsed — after which the first phone's
 * refresh must fail so it stops streaming.
 */
class DriverSessionServiceTest {

    private final Map<String, String> store = new HashMap<>();
    private DriverSessionService svc;

    private static String sessKey(long vehicleId) {
        return "driver:sess:" + vehicleId;
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenAnswer(inv -> store.get(inv.<String>getArgument(0)));
        doAnswer(inv -> {
            store.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(ops).set(anyString(), anyString(), any(Duration.class));
        when(redis.delete(anyString())).thenAnswer(inv -> store.remove(inv.<String>getArgument(0)) != null);
        svc = new DriverSessionService(redis);
    }

    @Test
    void blocksASecondDeviceWhileTheLeaseIsLive() {
        assertThat(svc.tryLogin(7, "devA")).isPresent();      // free vehicle → devA takes it
        assertThat(svc.tryLogin(7, "devB")).isEmpty();        // devB blocked, devA still driving
        assertThat(svc.tryLogin(7, "devA")).isPresent();      // same device may re-login (resume)
    }

    @Test
    void letsAnotherDeviceTakeOverOnlyAfterTheLeaseLapses() {
        String tokenA = svc.tryLogin(7, "devA").orElseThrow();
        assertThat(svc.refresh(tokenA)).isTrue();             // holder refreshes fine

        store.remove(sessKey(7));                             // lease lapses (TTL expiry)

        String tokenB = svc.tryLogin(7, "devB").orElseThrow(); // now devB may take the vehicle
        assertThat(svc.refresh(tokenB)).isTrue();
        assertThat(svc.refresh(tokenA)).isFalse();            // devA is out — must stop streaming
    }

    @Test
    void refreshOnUnknownTokenIsFalse() {
        assertThat(svc.refresh("never-issued")).isFalse();
        assertThat(svc.refresh(null)).isFalse();
    }

    @Test
    void logoutByHolderFreesTheVehicleAndInvalidatesTheToken() {
        String token = svc.tryLogin(7, "devA").orElseThrow();

        svc.logout(token);

        assertThat(svc.identify(token)).isEqualTo(Optional.empty());  // token gone
        assertThat(svc.tryLogin(7, "devB")).isPresent();              // vehicle freed for anyone
    }

    @Test
    void evictFreesTheVehicleRegardlessOfHolder() {
        svc.tryLogin(7, "devA");

        svc.evict(7);

        assertThat(svc.tryLogin(7, "devB")).isPresent();
    }
}
