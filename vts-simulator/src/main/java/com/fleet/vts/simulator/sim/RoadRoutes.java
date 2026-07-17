package com.fleet.vts.simulator.sim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleet.vts.simulator.config.SimulatorProperties;
import com.fleet.vts.simulator.model.GeoPoint;
import com.fleet.vts.simulator.model.Route;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builds real road-following routes via OSRM, so simulated land vehicles stay on roads
 * instead of cutting across terrain. Four waypoints around a city centre (back to the
 * first) yield a closed driving route snapped to the road network.
 *
 * <p>Returning {@code null} means "no road route", and callers must treat that as a
 * refusal rather than a licence to invent one: a land vehicle with no route stays parked.
 * There used to be synthetic fallback loops and straight lines for exactly this case,
 * which is how vehicles ended up driving across mountains and lakes whenever OSRM was
 * slow — and the public demo server it pointed at was slow often.
 *
 * <p>OSRM now runs locally (see the {@code osrm} service in docker-compose), so failures
 * are a real fault rather than someone else's rate limit. There is no permanent give-up:
 * a transient outage must not silently park the fleet forever, so every call retries and
 * failures are only rate-limited in the log.
 */
@Component
public class RoadRoutes {

    private static final Logger log = LoggerFactory.getLogger(RoadRoutes.class);
    /** Log at most one line per this many consecutive failures (avoid flooding). */
    private static final int LOG_EVERY = 50;

    private final SimulatorProperties props;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger failures = new AtomicInteger();

    public RoadRoutes(SimulatorProperties props) {
        this.props = props;
    }

    /** A road-following CLOSED loop near (lat, lon), or {@code null} to use a local loop. */
    public Route loopNear(double lat, double lon) {
        double r = 0.035; // ~3-4 km ring of waypoints around the centre
        return route(new double[][] {
                {lat + r, lon}, {lat, lon + r}, {lat - r, lon}, {lat, lon - r}, {lat + r, lon}
        }, true);
    }

    /**
     * The real driving route from A to B as an OPEN route, or {@code null} if routing is
     * unavailable. Open matters: its {@code totalKm} is the true road distance, which is
     * what the UI reports as "remaining km".
     */
    public Route routeBetween(double fromLat, double fromLon, double toLat, double toLon) {
        return route(new double[][] { {fromLat, fromLon}, {toLat, toLon} }, false);
    }

    /** A point snapped onto the road network, and how far (metres) the input was from it. */
    public record NearestRoad(double lat, double lon, double distanceMeters) {
    }

    /**
     * Snap (lat, lon) to the nearest drivable road via OSRM's nearest service, so an
     * operator move never drops a road vehicle inside a building or the sea. Returns
     * {@code null} if routing is unavailable — the caller then places the vehicle at the
     * clicked point unchanged.
     */
    public NearestRoad nearestRoad(double lat, double lon) {
        if (!props.isRoadRouting()) {
            return null;
        }
        try {
            String url = props.getOsrmBaseUrl() + "/nearest/v1/driving/" + lon + "," + lat + "?number=1";
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(6)).GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                return failNearest("HTTP " + res.statusCode());
            }
            JsonNode root = mapper.readTree(res.body());
            if (!"Ok".equals(root.path("code").asText())) {
                return failNearest("code=" + root.path("code").asText());
            }
            JsonNode wp = root.path("waypoints").path(0);
            JsonNode loc = wp.path("location");   // [lon, lat]
            if (!loc.isArray() || loc.size() < 2) {
                return failNearest("no waypoint");
            }
            failures.set(0);
            return new NearestRoad(loc.get(1).asDouble(), loc.get(0).asDouble(),
                    wp.path("distance").asDouble(0));
        } catch (Exception e) {
            return failNearest(e.getMessage());
        }
    }

    private NearestRoad failNearest(String why) {
        if (failures.incrementAndGet() % LOG_EVERY == 1) {
            log.warn("OSRM nearest failed ({}); placing at click without snapping.", why);
        }
        return null;
    }

    private Route route(double[][] waypoints, boolean closed) {
        if (!props.isRoadRouting()) {
            return null;
        }
        try {
            StringBuilder coords = new StringBuilder();
            for (double[] p : waypoints) {
                if (coords.length() > 0) {
                    coords.append(';');
                }
                coords.append(p[1]).append(',').append(p[0]); // OSRM wants lon,lat
            }
            String url = props.getOsrmBaseUrl() + "/route/v1/driving/" + coords
                    + "?overview=full&geometries=geojson&continue_straight=false";
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8)).GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                return fail("HTTP " + res.statusCode());
            }
            JsonNode root = mapper.readTree(res.body());
            if (!"Ok".equals(root.path("code").asText())) {
                return fail("code=" + root.path("code").asText());
            }
            JsonNode line = root.path("routes").path(0).path("geometry").path("coordinates");
            if (!line.isArray() || line.size() < 4) {
                return fail("too few points");
            }
            List<GeoPoint> geo = new ArrayList<>(line.size());
            for (JsonNode c : line) {
                geo.add(new GeoPoint(c.get(1).asDouble(), c.get(0).asDouble()));
            }
            failures.set(0);
            return new Route(geo, closed);
        } catch (Exception e) {
            return fail(e.getMessage());
        }
    }

    private Route fail(String why) {
        if (failures.incrementAndGet() % LOG_EVERY == 1) {
            log.warn("OSRM routing failed ({}); affected land vehicles stay parked until it recovers.",
                    why);
        }
        return null;
    }
}
