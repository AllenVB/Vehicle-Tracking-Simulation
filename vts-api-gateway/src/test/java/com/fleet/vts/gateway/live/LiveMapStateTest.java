package com.fleet.vts.gateway.live;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Delta (drain-changed) and viewport filtering behaviour of the live-map state. */
class LiveMapStateTest {

    private final LiveMapState state = new LiveMapState();

    private Position at(long id, double lat, double lon) {
        return new Position(id, lat, lon, 50, 90, 80, Instant.parse("2026-07-13T10:00:00Z"));
    }

    @Test
    void drainReturnsOnlyChangedThenResets() {
        state.update(at(1, 41.0, 29.0));
        state.update(at(2, 41.1, 29.1));

        List<Position> first = state.drainChanged();
        assertEquals(2, first.size());

        // nothing changed since the last drain
        assertTrue(state.drainChanged().isEmpty());

        // a single update yields a single-vehicle delta
        state.update(at(1, 41.05, 29.0));
        List<Position> second = state.drainChanged();
        assertEquals(1, second.size());
        assertEquals(1L, second.get(0).vehicleId());
    }

    /**
     * A device back from a coverage gap delivers an hour of history through the pipeline, and
     * all of it reaches here. Without the check the marker would jump to where the vehicle was
     * an hour ago and crawl forward again — the readings are real, they are just not "now".
     */
    @Test
    void anOlderReadingDoesNotMoveTheMarkerBackwards() {
        Position current = new Position(1L, 41.0, 29.0, 50, 90, 80,
                Instant.parse("2026-07-22T10:00:00Z"));
        Position buffered = new Position(1L, 39.0, 32.0, 60, 180, 75,
                Instant.parse("2026-07-22T08:00:00Z"));

        state.update(current);
        state.drainChanged();
        state.update(buffered);

        assertTrue(state.drainChanged().isEmpty(), "a stale reading is not a change");
        assertEquals(41.0, state.snapshot().get(0).lat());
    }

    @Test
    void viewportFilterKeepsOnlyInBoundsVehicles() {
        Bbox bbox = new Bbox(40.9, 28.9, 41.05, 29.05);
        List<Position> changed = List.of(at(1, 41.0, 29.0), at(2, 42.0, 30.0));

        List<Position> inView = state.withinViewport(changed, bbox);

        assertEquals(1, inView.size());
        assertEquals(1L, inView.get(0).vehicleId());
    }

    @Test
    void viewportRegistrationAndRemoval() {
        state.setViewport("session-1", new Bbox(0, 0, 1, 1));
        assertEquals(1, state.viewports().size());
        state.removeSession("session-1");
        assertTrue(state.viewports().isEmpty());
    }
}
