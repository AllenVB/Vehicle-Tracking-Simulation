package com.fleet.vts.ingestion.adapter.out.kafka;

import java.time.Instant;

/** Envelope written to the telemetry DLQ: the original payload plus why it failed. */
public record DlqRecord(String reason, Object payload, Instant timestamp) {
}
