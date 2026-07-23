package com.fleet.vts.gateway.admin;

import com.fleet.vts.common.topic.Topics;

import java.util.List;

/**
 * The dead-letter topics and how a DLQ maps back to the topic it should replay to.
 *
 * <p>Pure logic, no Kafka — so the mapping (which is the only part that can be subtly wrong)
 * is unit-tested on its own. Two shapes exist and they replay differently:
 * <ul>
 *   <li><b>Framework DLQs</b> ({@code vehicle.violation.dlq}, …): the dead-letter carries the
 *       original event bytes verbatim, so replay is a byte-for-byte republish to the source
 *       topic, which is the name with {@code .dlq} stripped.</li>
 *   <li><b>Telemetry DLQ</b> ({@code vehicle.telemetry.dlq}): each record is a {@code DlqRecord}
 *       envelope, and its source ({@code vehicle.telemetry.raw}) expects a resolved event, not
 *       the pre-resolution request inside the envelope. Republishing verbatim would just send
 *       it straight back. So it replays by re-ingestion, not by republish.</li>
 * </ul>
 */
public final class DlqTopics {

    private static final String SUFFIX = ".dlq";

    /** Every DLQ the platform creates (see the kafka-init container). */
    public static final List<String> ALL = List.of(
            Topics.TELEMETRY_DLQ,
            Topics.TELEMETRY_PROCESSED + SUFFIX,
            Topics.VIOLATION + SUFFIX,
            Topics.GEOFENCE_EVENT + SUFFIX,
            Topics.TRIP + SUFFIX,
            Topics.NOTIFICATION + SUFFIX);

    private DlqTopics() {
    }

    public static boolean isKnown(String dlqTopic) {
        return ALL.contains(dlqTopic);
    }

    /** The telemetry DLQ carries envelopes and cannot be verbatim-republished. */
    public static boolean isTelemetryEnvelope(String dlqTopic) {
        return Topics.TELEMETRY_DLQ.equals(dlqTopic);
    }

    /**
     * The topic a framework DLQ replays to: its name without {@code .dlq}.
     *
     * @throws IllegalArgumentException if called for the telemetry envelope DLQ, which has no
     *         verbatim source (it re-ingests instead)
     */
    public static String sourceTopic(String dlqTopic) {
        if (isTelemetryEnvelope(dlqTopic)) {
            throw new IllegalArgumentException("telemetry DLQ re-ingests, it has no verbatim source");
        }
        if (!dlqTopic.endsWith(SUFFIX)) {
            throw new IllegalArgumentException("not a DLQ topic: " + dlqTopic);
        }
        return dlqTopic.substring(0, dlqTopic.length() - SUFFIX.length());
    }
}
