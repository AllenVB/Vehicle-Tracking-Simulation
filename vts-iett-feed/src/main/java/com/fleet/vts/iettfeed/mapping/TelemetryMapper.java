package com.fleet.vts.iettfeed.mapping;

import com.fleet.vts.iettfeed.source.LiveReading;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Turns a neutral {@link LiveReading} into an ingestion {@link TelemetryRequest}:
 * assigns the seeded IMEI, derives speed/heading from motion, and infers
 * engine/ignition from movement. Returns empty when no IMEI is available (fleet
 * capacity reached), so the reading is dropped rather than dead-lettered.
 */
@Component
public class TelemetryMapper {

    private final ImeiAssigner imeiAssigner;
    private final MotionTracker motionTracker;

    public TelemetryMapper(ImeiAssigner imeiAssigner, MotionTracker motionTracker) {
        this.imeiAssigner = imeiAssigner;
        this.motionTracker = motionTracker;
    }

    public Optional<TelemetryRequest> toRequest(LiveReading r) {
        String imei = imeiAssigner.assign(r.vehicleId());
        if (imei == null) {
            return Optional.empty();
        }
        Motion motion = motionTracker.update(r.vehicleId(), r.lat(), r.lon(), r.ts());
        Boolean moving = motion.speedKmh() != null && motion.speedKmh() > 0 ? Boolean.TRUE : null;
        return Optional.of(new TelemetryRequest(
                imei, r.ts(), r.lat(), r.lon(),
                motion.speedKmh(), motion.heading(),
                null, null, moving, moving, null, null));
    }
}
