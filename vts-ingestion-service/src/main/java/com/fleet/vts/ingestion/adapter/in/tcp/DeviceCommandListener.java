package com.fleet.vts.ingestion.adapter.in.tcp;

import com.fleet.vts.common.event.DeviceCommandEvent;
import com.fleet.vts.common.teltonika.Codec12Codec;
import com.fleet.vts.common.topic.Topics;
import com.fleet.vts.ingestion.adapter.out.persistence.DeviceCommandStore;
import io.netty.buffer.Unpooled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Delivers operator commands to the device that is actually connected here.
 *
 * <p>Every ingestion instance reads every command. That is deliberate: a device's socket lives
 * on exactly one instance and nothing upstream knows which, so the alternative is a service
 * registry that has to stay correct across reconnects. Reading a message and discarding it is
 * cheaper than being wrong about where a socket is.
 *
 * <p>Which also means the "no session" answer is only honest once: an instance that does not
 * hold the device must stay quiet, or every instance would mark the command undelivered while
 * one of them was busy delivering it. So the timeout sweep — not the listener — is what turns
 * a command nobody could deliver into a visible outcome.
 */
@Component
public class DeviceCommandListener {

    private static final Logger log = LoggerFactory.getLogger(DeviceCommandListener.class);

    private final DeviceSessionRegistry sessions;
    private final DeviceCommandStore commands;
    private final TeltonikaMetrics metrics;
    private final int timeoutSeconds;

    public DeviceCommandListener(DeviceSessionRegistry sessions, DeviceCommandStore commands,
                                 TeltonikaMetrics metrics,
                                 @Value("${vts.ingestion.teltonika.command-timeout-seconds:60}")
                                 int timeoutSeconds) {
        this.sessions = sessions;
        this.commands = commands;
        this.metrics = metrics;
        this.timeoutSeconds = timeoutSeconds;
    }

    @KafkaListener(topics = Topics.DEVICE_COMMAND, containerFactory = "deviceCommandListenerFactory")
    public void onCommand(DeviceCommandEvent event) {
        if (event == null || event.imei() == null || event.command() == null) {
            return;
        }
        Optional<DeviceSessionRegistry.Session> session = sessions.find(event.imei());
        if (session.isEmpty()) {
            log.debug("No local session for {}; another instance may hold it", event.imei());
            return;
        }
        DeviceSessionRegistry.Session s = session.get();
        byte[] packet = Codec12Codec.encodeCommand(event.command());

        // Queue before writing. A device can answer faster than the write future completes, and
        // a response that arrives with an empty queue is discarded as unmatched.
        synchronized (s.awaitingResponse()) {
            s.awaitingResponse().addLast(event.commandId());
        }
        s.channel().writeAndFlush(Unpooled.wrappedBuffer(packet)).addListener(f -> {
            if (f.isSuccess()) {
                commands.markSent(event.commandId());
                metrics.commandsSent().increment();
                log.info("Command {} sent to {}: {}", event.commandId(), event.imei(), event.command());
            } else {
                synchronized (s.awaitingResponse()) {
                    s.awaitingResponse().remove(event.commandId());
                }
                commands.markFailed(event.commandId(), "WRITE_FAILED: " + f.cause());
            }
        });
    }

    /**
     * Close out commands nobody answered.
     *
     * <p>This covers both a device that took the instruction and said nothing, and one that was
     * never connected anywhere — from the operator's side those look the same, and both need to
     * stop reading as "in progress". Running on every instance is safe: the UPDATE is guarded on
     * the current status, so the second instance to try changes nothing.
     */
    @Scheduled(fixedDelay = 30_000)
    public void expireStaleCommands() {
        int expired = commands.expireStale(timeoutSeconds);
        if (expired > 0) {
            log.info("Expired {} unanswered device command(s)", expired);
        }
    }
}
