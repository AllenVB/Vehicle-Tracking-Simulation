package com.fleet.vts.common.teltonika;

import java.time.Instant;
import java.util.Map;

/**
 * One AVL record as a Teltonika device puts it on the wire.
 *
 * <p>Deliberately the device's view, not the platform's: coordinates are already scaled to
 * degrees but nothing else is interpreted. What a given IO id means is a device-configuration
 * question, so the ids stay as ids ({@link TeltonikaIo} names the ones this platform reads)
 * and translation happens at the edge, in the ingestion adapter.
 *
 * <p>{@code ts} is the moment the device *recorded* the fix, which is not the moment it
 * arrives. A device that has been out of coverage sends records hours old, in one burst, and
 * not necessarily in order. Everything downstream that treats arrival as "now" is wrong for
 * exactly that reason.
 */
public record AvlRecord(
        Instant ts,
        int priority,
        double lat,
        double lon,
        int altitudeMeters,
        int headingDegrees,
        int satellites,
        int speedKmh,
        int eventIoId,
        Map<Integer, Long> io
) {

    /**
     * Whether the record carries a usable GPS fix.
     *
     * <p>A device with no satellites still sends a record — with (0,0) coordinates — because
     * the point of the record is the IO state, not the position. Off the coast of Ghana is
     * not where the vehicle is.
     */
    public boolean hasFix() {
        return satellites > 0 && !(lat == 0.0 && lon == 0.0);
    }

    /** IO value, or {@code null} when the device did not report that id in this record. */
    public Long ioValue(int id) {
        return io.get(id);
    }
}
