package com.fleet.vts.common.topic;

/**
 * Kafka topic names and the fixed partition count. These constants are the
 * single source of truth shared by producers and consumers.
 *
 * <p>Partition count is 24 in every profile. It must never be raised after the
 * fact: changing it re-hashes vehicleId keys, breaking per-vehicle ordering and
 * the stateful Kafka Streams state stores.
 */
public final class Topics {

    private Topics() {
    }

    /** Fixed partition count for all vehicle-keyed topics (profile-independent). */
    public static final int PARTITIONS = 24;

    /** Raw telemetry published by ingestion, keyed by vehicleId. */
    public static final String TELEMETRY_RAW = "vehicle.telemetry.raw";

    /** Telemetry after processing, forwarded for UI fan-out. */
    public static final String TELEMETRY_PROCESSED = "vehicle.telemetry.processed";

    /** Poison telemetry payloads that failed validation/parsing. */
    public static final String TELEMETRY_DLQ = "vehicle.telemetry.dlq";

    /** Retry chain for transient processing failures (exponential backoff). */
    public static final String TELEMETRY_RETRY_5S = "vehicle.telemetry.raw.retry-5s";
    public static final String TELEMETRY_RETRY_1M = "vehicle.telemetry.raw.retry-1m";

    /** Violations produced by stateless (processing) and stateful (analytics) rules. */
    public static final String VIOLATION = "vehicle.violation";

    /** Geofence enter/exit events from the analytics topology. */
    public static final String GEOFENCE_EVENT = "vehicle.geofence.event";

    /** Trip open/close events from the analytics topology. */
    public static final String TRIP = "vehicle.trip";

    /** Driver-facing notifications produced by the notification service. */
    public static final String NOTIFICATION = "vehicle.notification";

    /** Broadcast to invalidate rule/threshold caches when a rule changes. */
    public static final String RULE_CACHE_INVALIDATION = "vehicle.rule.cache-invalidation";
}
