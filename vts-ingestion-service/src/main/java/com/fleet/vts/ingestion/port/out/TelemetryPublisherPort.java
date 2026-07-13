package com.fleet.vts.ingestion.port.out;

import com.fleet.vts.common.event.TelemetryEvent;

/** Secondary (driven) port for publishing telemetry to the message bus. */
public interface TelemetryPublisherPort {

    /** Publish a valid, resolved reading to the raw topic, keyed by vehicleId. */
    void publishRaw(TelemetryEvent event);

    /** Route a rejected payload to the dead-letter topic with a reason. */
    void publishDlq(Object payload, String reason);
}
