package com.fleet.vts.ingestion.adapter.in.tcp;

import com.fleet.vts.common.teltonika.AvlRecord;
import com.fleet.vts.common.teltonika.TeltonikaIo;
import com.fleet.vts.ingestion.adapter.in.web.TelemetryRequest;

/**
 * Translates a device's view of a reading into the platform's.
 *
 * <p>This is adapter work, not core work: the fact that ignition arrives as IO id 239 and
 * the odometer in metres is a property of Teltonika's format, and nothing past this class
 * should have to know it. The HTTP adapter's payload type is reused as the common shape, so
 * both transports converge before reaching the application core.
 */
final class AvlTelemetryMapper {

    private AvlTelemetryMapper() {
    }

    static TelemetryRequest toRequest(String imei, AvlRecord record, String correlationId) {
        Long ignition = record.ioValue(TeltonikaIo.IGNITION);
        Long movement = record.ioValue(TeltonikaIo.MOVEMENT);
        Long odometerMetres = record.ioValue(TeltonikaIo.TOTAL_ODOMETER_M);

        return new TelemetryRequest(
                imei,
                // The device's own clock, not arrival time. A buffered record is hours old and
                // saying otherwise here is what would make every downstream window wrong.
                record.ts(),
                record.lat(),
                record.lon(),
                // The protocol has no upper bound on speed, the platform's validation stops at
                // 400 km/h. Clamping rather than rejecting: a glitched speed field should not
                // cost the position that came with it.
                clamp(record.speedKmh(), 0, 400),
                // 360 and 0 are the same heading, and devices send both.
                record.headingDegrees() % 360,
                percent(record.ioValue(TeltonikaIo.BATTERY_LEVEL_PCT)),
                percent(record.ioValue(TeltonikaIo.FUEL_LEVEL_PCT)),
                // "Engine on" is movement when the device reports it, ignition otherwise: an
                // idling vehicle has the engine on and is not moving, so movement alone would
                // call it off. Ignition is the honest fallback.
                movement != null ? movement != 0 : (ignition != null && ignition != 0),
                ignition != null ? ignition != 0 : null,
                odometerMetres != null ? odometerMetres / 1000 : null,
                correlationId);
    }

    /** Percentages are stored as an integer 0..100; anything else is a misconfigured IO. */
    private static Integer percent(Long value) {
        return value == null ? null : clamp(value.intValue(), 0, 100);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
