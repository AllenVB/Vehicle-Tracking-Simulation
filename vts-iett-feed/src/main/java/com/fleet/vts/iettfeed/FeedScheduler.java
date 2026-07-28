package com.fleet.vts.iettfeed;

import com.fleet.vts.iettfeed.ingest.IngestionClient;
import com.fleet.vts.iettfeed.mapping.TelemetryMapper;
import com.fleet.vts.iettfeed.mapping.TelemetryRequest;
import com.fleet.vts.iettfeed.source.LiveReading;
import com.fleet.vts.iettfeed.source.LiveVehicleSource;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Every tick: pull live positions from the source, map each to an ingestion
 * telemetry request, and POST the batch. Disabled by setting
 * {@code vts.iett.enabled=false}. One tick never overlaps another (fixedDelay
 * waits for completion), so the İETT feed's slowness cannot pile up.
 */
@Component
@ConditionalOnProperty(prefix = "vts.iett", name = "enabled", havingValue = "true", matchIfMissing = true)
public class FeedScheduler {

    private static final Logger log = LoggerFactory.getLogger(FeedScheduler.class);

    private final LiveVehicleSource source;
    private final TelemetryMapper mapper;
    private final IngestionClient ingestion;
    private final Counter sent;

    public FeedScheduler(LiveVehicleSource source, TelemetryMapper mapper,
                         IngestionClient ingestion, MeterRegistry registry) {
        this.source = source;
        this.mapper = mapper;
        this.ingestion = ingestion;
        this.sent = Counter.builder("iett.readings.sent").register(registry);
    }

    @Scheduled(fixedDelayString = "${vts.iett.poll-interval-ms:15000}",
            initialDelayString = "${vts.iett.initial-delay-ms:5000}")
    public void tick() {
        List<LiveReading> readings = source.poll();
        if (readings.isEmpty()) {
            log.info("İETT feed: konum gelmedi (servis kapalı/boş olabilir)");
            return;
        }
        List<TelemetryRequest> batch = new ArrayList<>(readings.size());
        for (LiveReading r : readings) {
            try {
                mapper.toRequest(r).ifPresent(batch::add);
            } catch (Exception e) {
                // One malformed reading must not sink the whole batch.
                log.warn("Okuma map'lenemedi (vehicle={}): {}", r.vehicleId(), e.getMessage());
            }
        }
        boolean accepted = ingestion.sendBatch(batch);
        if (accepted) {
            sent.increment(batch.size());
        }
        log.info("İETT feed: {} konum çekildi, {} telemetri gönderildi (accepted={})",
                readings.size(), batch.size(), accepted);
    }
}
