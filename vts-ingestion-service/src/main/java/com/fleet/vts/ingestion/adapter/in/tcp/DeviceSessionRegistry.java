package com.fleet.vts.ingestion.adapter.in.tcp;

import io.netty.channel.Channel;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which devices this instance currently holds a socket to.
 *
 * <p>A command can only be delivered by the instance the device happens to be connected to,
 * and nothing outside that instance knows which one that is. So every instance keeps its own
 * registry, reads every command, and acts only on the ones it can.
 */
@Component
public class DeviceSessionRegistry {

    /** One entry per live session: the channel to write to, and the commands awaiting an answer. */
    public record Session(String imei, Channel channel, Deque<Long> awaitingResponse) {

        public Session(String imei, Channel channel) {
            this(imei, channel, new ArrayDeque<>());
        }
    }

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public DeviceSessionRegistry(MeterRegistry registry) {
        Gauge.builder("device.sessions.open", sessions, Map::size)
                .description("Device TCP sessions held by this instance")
                .register(registry);
    }

    void register(String imei, Channel channel) {
        // A device that reconnects before the old socket is reaped would otherwise leave the
        // registry pointing at a channel nothing will ever read from again.
        Session previous = sessions.put(imei, new Session(imei, channel));
        if (previous != null && previous.channel() != channel && previous.channel().isActive()) {
            previous.channel().close();
        }
    }

    void unregister(String imei, Channel channel) {
        // Compare the channel: a reconnect may already have replaced this entry, and removing
        // it here would silently drop the live session instead of the dead one.
        sessions.computeIfPresent(imei, (k, s) -> s.channel() == channel ? null : s);
    }

    public Optional<Session> find(String imei) {
        return Optional.ofNullable(sessions.get(imei))
                .filter(s -> s.channel().isActive());
    }

    public int size() {
        return sessions.size();
    }
}
