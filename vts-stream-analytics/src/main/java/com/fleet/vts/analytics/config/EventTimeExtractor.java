package com.fleet.vts.analytics.config;

import com.fleet.vts.common.event.GeofenceEvent;
import com.fleet.vts.common.event.TelemetryEvent;
import com.fleet.vts.common.event.TripEvent;
import com.fleet.vts.common.event.ViolationEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.processor.TimestampExtractor;

import java.time.Instant;

/**
 * Makes stream time mean "when the vehicle was there", not "when we heard about it".
 *
 * <p>Kafka's record timestamp is written by the producer, so on this pipeline it is ingestion
 * time. That was indistinguishable from event time for as long as the only source was a
 * simulator posting readings the instant it made them. A real tracker does not: it buffers
 * through coverage gaps and delivers hours of history at once. With the default extractor,
 * every one of those readings would be treated as having happened on arrival — a trip would
 * close in the middle, a 09:00 speeding window would be filled with 11:00 records, and a
 * violation from Tuesday would be counted on Wednesday. None of it would raise an error.
 *
 * <p>Every event on this pipeline carries its own instant, so the extractor reads that.
 * A record whose timestamp is missing or unusable falls back to the broker's, which keeps a
 * malformed event from poisoning stream time for its whole partition.
 */
public class EventTimeExtractor implements TimestampExtractor {

    @Override
    public long extract(ConsumerRecord<Object, Object> record, long partitionTime) {
        Instant eventTime = eventTimeOf(record.value());
        if (eventTime != null) {
            return eventTime.toEpochMilli();
        }
        // Negative would make Streams drop the record; the broker timestamp is the honest
        // second-best and is never negative.
        return record.timestamp();
    }

    private Instant eventTimeOf(Object value) {
        return switch (value) {
            case TelemetryEvent e -> e.ts();
            case ViolationEvent e -> e.occurredAt();
            case GeofenceEvent e -> e.occurredAt();
            // A closed trip's time is when it ended; an open one has only a start.
            case TripEvent e -> e.endedAt() != null ? e.endedAt() : e.startedAt();
            case null, default -> null;
        };
    }
}
