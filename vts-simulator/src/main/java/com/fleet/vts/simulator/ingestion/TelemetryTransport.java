package com.fleet.vts.simulator.ingestion;

import java.util.List;

/**
 * How a tick's readings leave the simulator.
 *
 * <p>Two implementations, and the difference is not cosmetic. {@link HttpTelemetryTransport}
 * posts JSON — convenient, and the shape this platform was built against. The Teltonika
 * transport speaks the binary protocol real hardware speaks, over a raw socket it holds open,
 * and buffers whatever it cannot deliver.
 *
 * <p>Keeping both is the point: HTTP is the contract for anything that is software, and the
 * binary path is the one that tells the truth about what a device does.
 */
public interface TelemetryTransport {

    /** Deliver (or buffer) one tick's worth of readings. Never throws. */
    void send(List<TelemetryPayload> readings);

    /** Name shown in logs and the operator console. */
    String name();
}
