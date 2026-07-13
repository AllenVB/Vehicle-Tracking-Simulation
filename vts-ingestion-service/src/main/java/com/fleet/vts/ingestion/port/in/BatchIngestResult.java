package com.fleet.vts.ingestion.port.in;

/** Summary of a batch ingest: how many were accepted vs dead-lettered. */
public record BatchIngestResult(int accepted, int deadLettered) {

    public int total() {
        return accepted + deadLettered;
    }
}
