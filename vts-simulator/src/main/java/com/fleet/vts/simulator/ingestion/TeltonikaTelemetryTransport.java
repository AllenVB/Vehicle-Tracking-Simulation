package com.fleet.vts.simulator.ingestion;

import com.fleet.vts.common.teltonika.AvlRecord;
import com.fleet.vts.common.teltonika.Codec8Codec;
import com.fleet.vts.common.teltonika.TeltonikaIo;
import com.fleet.vts.simulator.config.SimulatorProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Turns the fleet into a fleet of devices.
 *
 * <p>Each vehicle gets an {@link EmulatedDevice} holding its own socket and flash buffer, so
 * "the simulator is up" and "the platform is receiving from that vehicle" stop being the same
 * statement. Flushes run on virtual threads: a hundred devices blocking on a socket write is
 * exactly the workload they were added for, and it keeps one stalled device from delaying
 * the tick for the rest.
 */
@Component
public class TeltonikaTelemetryTransport implements TelemetryTransport {

    private static final Logger log = LoggerFactory.getLogger(TeltonikaTelemetryTransport.class);

    private final SimulatorProperties.Teltonika config;
    private final int codecId;
    private final Map<String, EmulatedDevice> devices = new ConcurrentHashMap<>();
    private final Counter delivered;
    private final Counter recorded;

    public TeltonikaTelemetryTransport(SimulatorProperties properties, MeterRegistry registry) {
        this.config = properties.getTeltonika();
        this.codecId = "8".equals(config.getCodec()) ? Codec8Codec.CODEC_8 : Codec8Codec.CODEC_8_EXTENDED;
        this.recorded = Counter.builder("device.emulator.recorded")
                .description("Readings written to a device buffer").register(registry);
        this.delivered = Counter.builder("device.emulator.delivered")
                .description("Records acknowledged by the server").register(registry);
        Gauge.builder("device.emulator.buffered", this, TeltonikaTelemetryTransport::totalBuffered)
                .description("Records currently held in device buffers").register(registry);
        log.info("Device emulator: Codec {} to {}:{}", config.getCodec(), config.getHost(), config.getPort());
    }

    @Override
    public void send(List<TelemetryPayload> readings) {
        for (TelemetryPayload reading : readings) {
            device(reading.imei()).record(toAvl(reading));
            recorded.increment();
        }
        flushAll();
    }

    private void flushAll() {
        try (ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Integer>> futures = new ArrayList<>(devices.size());
            for (EmulatedDevice device : devices.values()) {
                futures.add(vt.submit(device::flush));
            }
            for (Future<Integer> f : futures) {
                try {
                    delivered.increment(f.get());
                } catch (Exception e) {
                    log.warn("Device flush failed: {}", e.getMessage());
                }
            }
        }
    }

    private EmulatedDevice device(String imei) {
        return devices.computeIfAbsent(imei, id -> new EmulatedDevice(
                id, config.getHost(), config.getPort(), codecId,
                config.getBufferSize(), config.getMaxRecordsPerPacket(),
                (int) config.getTimeout().toMillis()));
    }

    /**
     * Silence a device for a while: it keeps recording, sends nothing, and empties everything
     * at once when the window closes. That is the store-and-forward case, and the only way to
     * see whether the pipeline handles two-hour-old readings is to produce some.
     */
    public Map<String, Object> silence(String imei, long seconds) {
        EmulatedDevice device = device(imei);
        device.goSilent(seconds);
        // Drop the socket too: a device out of coverage has no connection, and reconnecting
        // is part of what has to work.
        device.closeQuietly();
        return Map.of("imei", imei, "silentSeconds", seconds, "buffered", device.buffered());
    }

    /** Buffer depth and silence state per device, for the operator console. */
    public Map<String, Object> status() {
        Map<String, Object> perDevice = new LinkedHashMap<>();
        long silent = 0;
        for (Map.Entry<String, EmulatedDevice> e : devices.entrySet()) {
            EmulatedDevice d = e.getValue();
            if (d.buffered() > 0 || d.silent()) {
                perDevice.put(e.getKey(), Map.of(
                        "buffered", d.buffered(),
                        "silent", d.silent(),
                        "dropped", d.dropped()));
            }
            if (d.silent()) {
                silent++;
            }
        }
        return Map.of(
                "transport", name(),
                "codec", config.getCodec(),
                "devices", devices.size(),
                "silentDevices", silent,
                "totalBuffered", totalBuffered(),
                "backlog", perDevice);
    }

    private double totalBuffered() {
        return devices.values().stream().mapToInt(EmulatedDevice::buffered).sum();
    }

    private AvlRecord toAvl(TelemetryPayload p) {
        Map<Integer, Long> io = new LinkedHashMap<>();
        io.put(TeltonikaIo.IGNITION, p.ignition() ? 1L : 0L);
        io.put(TeltonikaIo.MOVEMENT, p.speedKmh() > 0 ? 1L : 0L);
        io.put(TeltonikaIo.BATTERY_LEVEL_PCT, (long) p.battery());
        io.put(TeltonikaIo.FUEL_LEVEL_PCT, (long) p.fuelPct());
        io.put(TeltonikaIo.EXTERNAL_VOLTAGE_MV, p.engineOn() ? 13_800L : 12_200L);
        io.put(TeltonikaIo.TOTAL_ODOMETER_M, p.odometerKm() * 1000);
        io.put(TeltonikaIo.GSM_SIGNAL, 4L);

        // Satellite count is what tells the server the fix is real; a device with none sends
        // (0,0) and the server drops the position. Nine is an ordinary open-sky figure.
        return new AvlRecord(p.ts(), 1, p.lat(), p.lon(), 850, p.heading(), 9,
                p.speedKmh(), 0, io);
    }

    @PreDestroy
    void closeAll() {
        devices.values().forEach(EmulatedDevice::closeQuietly);
    }

    @Override
    public String name() {
        return "TELTONIKA/" + config.getCodec();
    }
}
