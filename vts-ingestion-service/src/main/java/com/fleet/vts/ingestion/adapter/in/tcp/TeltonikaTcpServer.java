package com.fleet.vts.ingestion.adapter.in.tcp;

import com.fleet.vts.ingestion.port.in.TelemetryInboundPort;
import com.fleet.vts.ingestion.port.out.VehicleLookupPort;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * The raw-TCP door devices actually knock on.
 *
 * <p>Ingestion already separated ports from adapters, so this is a second primary adapter
 * next to the HTTP one and the application core is untouched. Both remain live: HTTP is how
 * this platform's own components talk to it, TCP is how hardware does.
 *
 * <p>Two thread pools, not one. Netty's event loops must never block, and this session does
 * block — an IMEI lookup can miss both caches and reach Postgres, and publishing goes to
 * Kafka. So the framing runs on the event loop and the session handler runs on a separate
 * {@link DefaultEventExecutorGroup}, which still pins each channel to a single thread and so
 * keeps one device's packets in order.
 */
@Component
@EnableConfigurationProperties(TeltonikaProperties.class)
@ConditionalOnProperty(prefix = "vts.ingestion.teltonika", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class TeltonikaTcpServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TeltonikaTcpServer.class);

    private final TeltonikaProperties properties;
    private final TelemetryInboundPort inbound;
    private final VehicleLookupPort lookup;
    private final TeltonikaMetrics metrics;
    private final DeviceSessionRegistry sessions;
    private final com.fleet.vts.ingestion.adapter.out.persistence.DeviceCommandStore commands;

    private EventLoopGroup acceptors;
    private EventLoopGroup workers;
    private EventExecutorGroup sessionExecutors;
    private Channel serverChannel;
    private volatile boolean running;

    public TeltonikaTcpServer(TeltonikaProperties properties, TelemetryInboundPort inbound,
                              VehicleLookupPort lookup, TeltonikaMetrics metrics,
                              DeviceSessionRegistry sessions,
                              com.fleet.vts.ingestion.adapter.out.persistence.DeviceCommandStore commands) {
        this.properties = properties;
        this.inbound = inbound;
        this.lookup = lookup;
        this.metrics = metrics;
        this.sessions = sessions;
        this.commands = commands;
    }

    @Override
    public void start() {
        acceptors = new NioEventLoopGroup(1);
        workers = new NioEventLoopGroup();
        sessionExecutors = new DefaultEventExecutorGroup(
                Math.max(4, Runtime.getRuntime().availableProcessors() * 2));

        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(acceptors, workers)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 256)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                // Devices send small packets and wait for the ACK; Nagle would add a delay to
                // every single one of them for no gain.
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast(new ReadTimeoutHandler(
                                        properties.getIdleTimeout().toSeconds(), TimeUnit.SECONDS))
                                .addLast(new TeltonikaFrameDecoder(properties.getMaxPacketBytes()))
                                .addLast(sessionExecutors, new TeltonikaSessionHandler(
                                        inbound, lookup, metrics, sessions, commands));
                    }
                });

        try {
            serverChannel = bootstrap.bind(properties.getPort()).sync().channel();
            running = true;
            log.info("Teltonika Codec 8/8E listener on tcp/{}", properties.getPort());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while binding tcp/" + properties.getPort(), e);
        }
    }

    @Override
    public void stop() {
        running = false;
        if (serverChannel != null) {
            serverChannel.close().awaitUninterruptibly();
        }
        // Graceful: in-flight packets get acknowledged, so devices do not resend them.
        shutdown(sessionExecutors);
        shutdown(workers);
        shutdown(acceptors);
        log.info("Teltonika listener stopped");
    }

    private void shutdown(EventExecutorGroup group) {
        if (group != null) {
            group.shutdownGracefully(0, 5, TimeUnit.SECONDS).awaitUninterruptibly();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** The bound port, which is the configured one unless 0 was requested (tests). */
    public int boundPort() {
        return serverChannel == null ? -1
                : ((java.net.InetSocketAddress) serverChannel.localAddress()).getPort();
    }
}
