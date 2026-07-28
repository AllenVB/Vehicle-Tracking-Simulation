package com.fleet.vts.iettfeed.mapping;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Speed derivation, bearing derivation and jitter suppression. */
class MotionTrackerTest {

    private final MotionTracker tracker = new MotionTracker();

    private static final Instant T0 = Instant.parse("2026-07-28T13:00:00Z");
    private static final Instant T10 = T0.plusSeconds(10);
    private static final Instant T20 = T10.plusSeconds(10);

    @Test
    void firstSightingIsUnknown() {
        assertThat(tracker.update("bus", 41.0, 29.0, T0)).isEqualTo(Motion.UNKNOWN);
    }

    @Test
    void derivesNorthwardHeadingAndSpeed() {
        tracker.update("bus", 41.000, 29.0, T0);
        // ~111 m north over 10 s ≈ 40 km/h, bearing 0
        Motion m = tracker.update("bus", 41.001, 29.0, T10);

        assertThat(m.heading()).isEqualTo(0);
        assertThat(m.speedKmh()).isCloseTo(40, within(2));
    }

    @Test
    void derivesEastwardHeading() {
        tracker.update("bus", 41.0, 29.000, T0);
        Motion m = tracker.update("bus", 41.0, 29.001, T10);

        assertThat(m.heading()).isCloseTo(90, within(1));
        assertThat(m.speedKmh()).isPositive();
    }

    @Test
    void tinyJitterIsStationaryAndKeepsHeading() {
        tracker.update("bus", 41.000, 29.0, T0);
        Motion moving = tracker.update("bus", 41.001, 29.0, T10); // establishes heading 0
        // ~1 m nudge: below the 8 m threshold → speed 0, heading unchanged
        Motion m = tracker.update("bus", 41.00101, 29.0, T20);

        assertThat(m.speedKmh()).isZero();
        assertThat(m.heading()).isEqualTo(moving.heading());
    }

    @Test
    void subThresholdMoveOnFirstEverPairDoesNotThrow() {
        // First sighting stores heading=null; a tiny nudge next tick must NOT unbox it.
        tracker.update("bus", 41.000000, 29.0, T0);
        Motion m = tracker.update("bus", 41.0000005, 29.0, T10); // ~0.05 m, below threshold

        assertThat(m.speedKmh()).isZero();
        assertThat(m.heading()).isNull(); // still unknown, but no NPE
    }

    @Test
    void speedNullWhenNoTimestamps() {
        tracker.update("bus", 41.0, 29.0, null);
        Motion m = tracker.update("bus", 41.001, 29.0, null);

        assertThat(m.speedKmh()).isNull();
        assertThat(m.heading()).isEqualTo(0); // heading still derivable from position
    }

    @Test
    void speedClampedToCeiling() {
        tracker.update("bus", 41.0, 29.0, T0);
        // ~1.1 km jump in 1 s would be ~4000 km/h → clamped to 400
        Motion m = tracker.update("bus", 41.01, 29.0, T0.plusSeconds(1));

        assertThat(m.speedKmh()).isEqualTo(400);
    }
}
