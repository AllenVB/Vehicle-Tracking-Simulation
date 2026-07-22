package com.fleet.vts.ingestion.adapter.in.tcp;

import com.fleet.vts.common.teltonika.AvlRecord;
import com.fleet.vts.common.teltonika.Codec12Codec;
import com.fleet.vts.common.teltonika.Codec8Codec;
import com.fleet.vts.common.teltonika.TeltonikaProtocolException;
import com.fleet.vts.ingestion.adapter.out.persistence.DeviceCommandStore;
import com.fleet.vts.ingestion.adapter.in.web.TelemetryRequest;
import com.fleet.vts.ingestion.port.in.BatchIngestResult;
import com.fleet.vts.ingestion.port.in.TelemetryInboundPort;
import com.fleet.vts.ingestion.port.out.VehicleLookupPort;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.ReadTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One device session: handshake, then a stream of AVL packets, each acknowledged.
 *
 * <p>The acknowledgement is the whole protocol. A device holds every record until the server
 * confirms the count, and resends everything it has not heard back about. That makes the ACK
 * the point where delivery becomes the platform's problem — and it is also why the ACK
 * reports how many records were *parsed*, not how many were business-accepted. A record for
 * an unknown vehicle is dead-lettered here; telling the device it never arrived would make it
 * resend the same record until the device is retired.
 *
 * <p>Deliberately not {@code @Sharable}: it holds the session's IMEI, so one instance per
 * connection is the point.
 */
class TeltonikaSessionHandler extends SimpleChannelInboundHandler<Object> {

    private static final Logger log = LoggerFactory.getLogger(TeltonikaSessionHandler.class);

    private static final byte HANDSHAKE_ACCEPT = 0x01;
    private static final byte HANDSHAKE_REJECT = 0x00;

    /** Beyond this, a record's timestamp is treated as a device clock fault rather than a delay. */
    private static final Duration MAX_BACKDATING = Duration.ofDays(7);

    private final TelemetryInboundPort inbound;
    private final VehicleLookupPort lookup;
    private final TeltonikaMetrics metrics;
    private final DeviceSessionRegistry sessions;
    private final DeviceCommandStore commands;

    private String imei;

    TeltonikaSessionHandler(TelemetryInboundPort inbound, VehicleLookupPort lookup,
                            TeltonikaMetrics metrics, DeviceSessionRegistry sessions,
                            DeviceCommandStore commands) {
        this.inbound = inbound;
        this.lookup = lookup;
        this.metrics = metrics;
        this.sessions = sessions;
        this.commands = commands;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        metrics.connections().increment();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (imei == null) {
            return;
        }
        // Drain before unregistering: anything written to this socket and not yet answered
        // never will be, and an operator watching a command sit on SENT forever learns nothing.
        sessions.find(imei)
                .filter(s -> s.channel() == ctx.channel())
                .ifPresent(s -> {
                    Long id;
                    while ((id = s.awaitingResponse().poll()) != null) {
                        commands.markFailed(id, "SESSION_CLOSED");
                    }
                });
        sessions.unregister(imei, ctx.channel());
        log.info("Device {} disconnected", imei);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object frame) {
        if (frame instanceof TeltonikaFrames.Imei(String announced)) {
            handleHandshake(ctx, announced);
        } else if (frame instanceof TeltonikaFrames.Avl(byte[] packet)) {
            handlePacket(ctx, packet);
        }
    }

    private void handleHandshake(ChannelHandlerContext ctx, String announced) {
        // Rejecting an unknown device at the handshake, rather than dead-lettering every
        // record it then sends, is what the one accept/reject byte is for.
        if (lookup.findByImei(announced).isEmpty()) {
            metrics.rejectedDevices().increment();
            log.warn("Rejecting unknown device {} from {}", announced, ctx.channel().remoteAddress());
            ctx.writeAndFlush(Unpooled.wrappedBuffer(new byte[]{HANDSHAKE_REJECT}))
                    .addListener(f -> ctx.close());
            return;
        }
        this.imei = announced;
        sessions.register(announced, ctx.channel());
        log.info("Device {} connected from {}", announced, ctx.channel().remoteAddress());
        ctx.writeAndFlush(Unpooled.wrappedBuffer(new byte[]{HANDSHAKE_ACCEPT}));
    }

    /**
     * A framed packet is not necessarily telemetry any more: the same socket now carries a
     * device's answer to a command. The frame is identical either way, so the codec id decides.
     */
    private void handlePacket(ChannelHandlerContext ctx, byte[] packet) {
        int codecId;
        try {
            codecId = Codec12Codec.codecIdOf(packet);
        } catch (TeltonikaProtocolException e) {
            metrics.malformedPackets().increment();
            log.warn("Unreadable packet from {}: {}", imei, e.getMessage());
            return;
        }
        if (codecId == Codec12Codec.CODEC_12) {
            handleCommandResponse(packet);
        } else {
            handleAvl(ctx, packet);
        }
    }

    /**
     * Match a response to the command it answers.
     *
     * <p>Codec 12 carries no correlation id — the device simply replies on the socket it was
     * asked on. Matching is therefore positional: commands are queued in the order they were
     * written and answered in that same order. This is why one device's commands are never
     * sent concurrently.
     */
    private void handleCommandResponse(byte[] packet) {
        String response;
        try {
            response = Codec12Codec.decodeResponse(packet);
        } catch (TeltonikaProtocolException e) {
            metrics.malformedPackets().increment();
            log.warn("Unreadable command response from {}: {}", imei, e.getMessage());
            return;
        }
        Long commandId = sessions.find(imei)
                .map(s -> s.awaitingResponse().poll())
                .orElse(null);
        if (commandId == null) {
            // The command already timed out, or the device volunteered something nobody asked
            // for. Recorded, not applied: overwriting a closed command would rewrite history.
            log.warn("Unmatched response from {}: {}", imei, response);
            metrics.unmatchedResponses().increment();
            return;
        }
        commands.markAnswered(commandId, response);
        metrics.commandResponses().increment();
        log.info("Device {} answered command {}: {}", imei, commandId, response);
    }

    private void handleAvl(ChannelHandlerContext ctx, byte[] packet) {
        List<AvlRecord> records;
        try {
            records = Codec8Codec.decode(packet).records();
        } catch (TeltonikaProtocolException e) {
            // No ACK: the device will resend. A packet that failed its own CRC is a broken
            // transmission, and pretending to have taken it loses the records for good.
            metrics.malformedPackets().increment();
            log.warn("Discarding malformed packet from {}: {}", imei, e.getMessage());
            return;
        }

        String correlationId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        List<TelemetryRequest> requests = new ArrayList<>(records.size());
        long lateMillis = 0;
        int withoutFix = 0;

        for (AvlRecord record : records) {
            if (!record.hasFix()) {
                // Kept out of the pipeline but still acknowledged: the device is reporting IO
                // state with no position, and (0,0) is not a place the fleet has ever been.
                withoutFix++;
                continue;
            }
            if (record.ts().isBefore(now.minus(MAX_BACKDATING))
                    || record.ts().isAfter(now.plus(Duration.ofHours(1)))) {
                metrics.badClockRecords().increment();
                continue;
            }
            lateMillis = Math.max(lateMillis, now.toEpochMilli() - record.ts().toEpochMilli());
            requests.add(AvlTelemetryMapper.toRequest(imei, record, correlationId));
        }

        BatchIngestResult result = requests.isEmpty()
                ? new BatchIngestResult(0, 0)
                : inbound.ingestBatch(requests);

        metrics.packets().increment();
        metrics.records().increment(records.size());
        if (lateMillis > 0) {
            metrics.recordLateness(Duration.ofMillis(lateMillis));
        }
        if (records.size() > 1) {
            log.info("Device {} delivered {} buffered record(s) in one packet, oldest {} s late",
                    imei, records.size(), lateMillis / 1000);
        }
        if (withoutFix > 0) {
            log.debug("Device {}: {} record(s) had no GPS fix", imei, withoutFix);
        }

        // The device counts records, not accepted business events.
        ctx.writeAndFlush(Unpooled.copyInt(records.size()));
        log.debug("ACK {} record(s) to {} (accepted {}, dead-lettered {})",
                records.size(), imei, result.accepted(), result.deadLettered());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (cause instanceof ReadTimeoutException) {
            log.info("Closing idle session for device {}", imei);
        } else {
            metrics.protocolErrors().increment();
            log.warn("Closing session for device {}: {}", imei, cause.toString());
        }
        ctx.close();
    }
}
