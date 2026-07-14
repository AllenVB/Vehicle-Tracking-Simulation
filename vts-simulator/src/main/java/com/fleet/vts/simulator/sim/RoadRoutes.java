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
 * Builds real road-following loops via the OSRM routing API so simulated
 * vehicles stay on roads instead of cutting across terrain. Four waypoints
 * around a city centre (back to the first) yield a closed driving route snapped
 * to the road network. Degrades gracefully: if routing is disabled or OSRM is
 * unreachable, callers fall back to synthetic {@link RouteFactory} loops, and
 * after a few failures we stop calling OSRM to keep startup fast.
 */
@Component
public class RoadRoutes {

    private static final Logger log = LoggerFactory.getLogger(RoadRoutes.class);
    private static final int MAX_FAILURES = 3;

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

    private Route route(double[][] waypoints, boolean closed) {
        if (!props.isRoadRouting() || failures.get() >= MAX_FAILURES) {
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
        int n = failures.incrementAndGet();
        if (n == 1 || n == MAX_FAILURES) {
            log.warn("OSRM routing failed ({}); using local loops{}.",
                    why, n >= MAX_FAILURES ? " for the rest of the fleet" : "");
        }
        return null;
    }
}
