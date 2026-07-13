package com.fleet.vts.ingestion.port.in;

/**
 * Outcome of ingesting a single reading: either accepted to the raw topic or
 * dead-lettered with a reason.
 */
public record IngestResult(boolean accepted, String status) {

    public static IngestResult ok() {
        return new IngestResult(true, "ACCEPTED");
    }

    public static IngestResult deadLettered(String reason) {
        return new IngestResult(false, "DEAD_LETTERED:" + reason);
    }
}
