package com.fleet.vts.simulator.fuel;

import com.fleet.vts.simulator.model.GeoPoint;
import com.fleet.vts.simulator.sim.GeoUtils;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * The fuel stations a low tank can be sent to.
 *
 * <p>Fetched from ingestion rather than held as a constant here, so the stations vehicles
 * drive to are the same rows the operator's map draws — a second hard-coded copy would drift
 * from the database the moment either side changed.
 *
 * <p>The whole list (~160 rows) is cached in memory and the nearest is picked locally,
 * because this question gets asked on the simulation's decision path: a per-vehicle HTTP
 * round-trip there would be the same mistake as routing on the tick path.
 */
@Component
public class FuelStations {

    private static final Logger log = LoggerFactory.getLogger(FuelStations.class);
    private static final String PATH = "/api/v1/reference/fuel-stations";

    /** One station as ingestion reports it. */
    public record Station(Long id, String name, String brand, double lat, double lon) {

        public GeoPoint point() {
            return new GeoPoint(lat, lon);
        }
    }

    private final RestClient ingestion;
    private volatile List<Station> stations = List.of();

    /**
     * Marked explicitly because the test constructor below makes this class ambiguous to
     * Spring, which then falls back to looking for a no-arg constructor and fails at startup.
     */
    @Autowired
    public FuelStations(RestClient ingestionRestClient) {
        this.ingestion = ingestionRestClient;
    }

    /** Test constructor: preloaded stations, no HTTP. */
    public FuelStations(List<Station> stations) {
        this.ingestion = null;
        this.stations = List.copyOf(stations);
    }

    /**
     * Reloaded on a timer as well as at startup: ingestion may still be coming up when the
     * simulator boots, and an empty list would otherwise strand every low tank for good.
     */
    @PostConstruct
    @Scheduled(fixedDelay = 300_000)
    public void load() {
        if (ingestion == null) {
            return;
        }
        try {
            Station[] fetched = ingestion.get().uri(PATH).retrieve().body(Station[].class);
            if (fetched != null && fetched.length > 0) {
                this.stations = List.of(fetched);
                log.info("Fuel stations loaded: {}", stations.size());
            }
        } catch (Exception e) {
            // Not fatal: vehicles keep driving, they just cannot be sent to refuel yet.
            log.warn("Could not load fuel stations ({}); retrying later", e.getMessage());
        }
    }

    /**
     * The station closest to {@code (lat, lon)} as the crow flies, or {@code null} if none are
     * loaded. Straight-line rather than driving distance: this only has to choose which station
     * to aim for, and asking OSRM for ~160 routes to answer that would cost far more than the
     * occasional case where the second-nearest is really the shorter drive.
     */
    public Station nearest(double lat, double lon) {
        GeoPoint from = new GeoPoint(lat, lon);
        Station best = null;
        double bestKm = Double.MAX_VALUE;
        for (Station s : stations) {
            double km = GeoUtils.haversineKm(from, s.point());
            if (km < bestKm) {
                bestKm = km;
                best = s;
            }
        }
        return best;
    }

    public boolean isEmpty() {
        return stations.isEmpty();
    }

    public int size() {
        return stations.size();
    }
}
