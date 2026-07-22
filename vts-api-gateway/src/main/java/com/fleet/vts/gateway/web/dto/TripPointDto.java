package com.fleet.vts.gateway.web.dto;

import java.time.Instant;

/**
 * One breadcrumb of a trip's route, in {@code seq} order. {@code speedKmh} is nullable —
 * the breadcrumb is rebuilt from telemetry, which does not always carry a speed.
 *
 * <p>{@code ts} was selected by the query from the start and then dropped on the floor here,
 * which is why the route could only ever be drawn as a static line. Playback needs it: the
 * points are ~30 s apart in real time, and a replay that steps through them at a fixed rate
 * shows a vehicle moving at a constant speed it never drove.
 */
public record TripPointDto(
        int seq,
        Instant ts,
        double lat,
        double lon,
        Integer speedKmh) {
}
