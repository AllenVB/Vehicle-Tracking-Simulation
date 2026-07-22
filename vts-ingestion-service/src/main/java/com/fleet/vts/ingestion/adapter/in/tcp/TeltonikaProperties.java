package com.fleet.vts.ingestion.adapter.in.tcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Settings for the raw-TCP device listener. */
@ConfigurationProperties(prefix = "vts.ingestion.teltonika")
public class TeltonikaProperties {

    /** Whether to open the TCP listener at all. The HTTP path works either way. */
    private boolean enabled = true;

    /** Teltonika's usual FMB server port. */
    private int port = 5027;

    /**
     * Hard ceiling on one AVL packet. 255 records of a few hundred bytes is the protocol's
     * own maximum; the limit exists so that a corrupted length field allocates a buffer that
     * is rejected rather than one that exhausts the heap.
     */
    private int maxPacketBytes = 262_144;

    /**
     * How long a connection may stay silent before it is closed.
     *
     * <p>Generous on purpose: a device buffering through a coverage gap says nothing for as
     * long as the gap lasts, and closing on it turns store-and-forward into a reconnect
     * storm. Half an hour is long enough for the gaps this platform expects and short enough
     * that a socket left open by a dead modem does not linger for a day.
     */
    private Duration idleTimeout = Duration.ofMinutes(30);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public int getMaxPacketBytes() {
        return maxPacketBytes;
    }

    public void setMaxPacketBytes(int maxPacketBytes) {
        this.maxPacketBytes = maxPacketBytes;
    }

    public Duration getIdleTimeout() {
        return idleTimeout;
    }

    public void setIdleTimeout(Duration idleTimeout) {
        this.idleTimeout = idleTimeout;
    }
}
