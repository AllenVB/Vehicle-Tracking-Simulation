package com.fleet.vts.iettfeed.mapping;

import com.fleet.vts.iettfeed.config.IettProperties;
import com.fleet.vts.iettfeed.source.LiveReading;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** End-to-end mapping: identity assignment, motion enrichment, engine inference. */
class TelemetryMapperTest {

    private final Instant t0 = Instant.parse("2026-07-28T13:00:00Z");

    private TelemetryMapper mapper(int capacity) {
        IettProperties props = new IettProperties();
        props.setMaxVehicles(capacity);
        return new TelemetryMapper(new ImeiAssigner(props), new MotionTracker());
    }

    @Test
    void firstReadingHasIdentityButNoMotion() {
        TelemetryMapper mapper = mapper(100);

        Optional<TelemetryRequest> req = mapper.toRequest(new LiveReading("C-290", 41.0, 29.0, t0));

        assertThat(req).isPresent();
        TelemetryRequest r = req.get();
        assertThat(r.imei()).isEqualTo("000000000000001");
        assertThat(r.lat()).isEqualTo(41.0);
        assertThat(r.lon()).isEqualTo(29.0);
        assertThat(r.ts()).isEqualTo(t0);
        assertThat(r.speedKmh()).isNull();
        assertThat(r.heading()).isNull();
        assertThat(r.engineOn()).isNull();
        assertThat(r.ignition()).isNull();
    }

    @Test
    void movingReadingInfersEngineOnFromSpeed() {
        TelemetryMapper mapper = mapper(100);

        mapper.toRequest(new LiveReading("C-290", 41.000, 29.0, t0));
        TelemetryRequest r = mapper.toRequest(
                new LiveReading("C-290", 41.001, 29.0, t0.plusSeconds(10))).orElseThrow();

        assertThat(r.speedKmh()).isPositive();
        assertThat(r.engineOn()).isTrue();
        assertThat(r.ignition()).isTrue();
        assertThat(r.heading()).isEqualTo(0);
    }

    @Test
    void dropsReadingWhenFleetCapacityReached() {
        TelemetryMapper mapper = mapper(1);

        assertThat(mapper.toRequest(new LiveReading("A", 41.0, 29.0, t0))).isPresent();
        assertThat(mapper.toRequest(new LiveReading("B", 41.0, 29.0, t0))).isEmpty();
    }
}
