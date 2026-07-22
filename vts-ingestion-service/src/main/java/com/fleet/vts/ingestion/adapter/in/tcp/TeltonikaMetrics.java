package com.fleet.vts.ingestion.adapter.in.tcp;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * What the device channel is doing, in numbers.
 *
 * <p>{@code device.record.lateness} is the one that matters: it is the gap between when a
 * device recorded a reading and when the platform received it. On the HTTP path that number
 * was always near zero by construction, which is precisely why nothing downstream was built
 * to survive it being large.
 */
@Component
public class TeltonikaMetrics {

    private final Counter connections;
    private final Counter rejectedDevices;
    private final Counter packets;
    private final Counter records;
    private final Counter malformedPackets;
    private final Counter badClockRecords;
    private final Counter protocolErrors;
    private final Counter commandsSent;
    private final Counter commandResponses;
    private final Counter unmatchedResponses;
    private final Timer lateness;

    public TeltonikaMetrics(MeterRegistry registry) {
        this.connections = Counter.builder("device.connections")
                .description("Accepted device TCP sessions").register(registry);
        this.rejectedDevices = Counter.builder("device.rejected")
                .description("Handshakes refused because the IMEI is unknown").register(registry);
        this.packets = Counter.builder("device.packets")
                .description("AVL packets parsed and acknowledged").register(registry);
        this.records = Counter.builder("device.records")
                .description("AVL records carried by those packets").register(registry);
        this.malformedPackets = Counter.builder("device.packets.malformed")
                .description("Packets discarded before the ACK (bad CRC, bad framing)").register(registry);
        this.badClockRecords = Counter.builder("device.records.bad_clock")
                .description("Records dropped: timestamp implausibly far from now").register(registry);
        this.protocolErrors = Counter.builder("device.protocol.errors")
                .description("Sessions closed on a protocol error").register(registry);
        this.commandsSent = Counter.builder("device.commands.sent")
                .description("Codec 12 commands written to a device socket").register(registry);
        this.commandResponses = Counter.builder("device.commands.answered")
                .description("Command responses matched to the command that asked").register(registry);
        this.unmatchedResponses = Counter.builder("device.commands.unmatched")
                .description("Responses with no outstanding command (usually a timed-out one)")
                .register(registry);
        this.lateness = Timer.builder("device.record.lateness")
                .description("Age of the oldest record in a packet when it arrived")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    public Counter connections() {
        return connections;
    }

    public Counter rejectedDevices() {
        return rejectedDevices;
    }

    public Counter packets() {
        return packets;
    }

    public Counter records() {
        return records;
    }

    public Counter malformedPackets() {
        return malformedPackets;
    }

    public Counter badClockRecords() {
        return badClockRecords;
    }

    public Counter protocolErrors() {
        return protocolErrors;
    }

    public Counter commandsSent() {
        return commandsSent;
    }

    public Counter commandResponses() {
        return commandResponses;
    }

    public Counter unmatchedResponses() {
        return unmatchedResponses;
    }

    public void recordLateness(Duration age) {
        lateness.record(age);
    }
}
