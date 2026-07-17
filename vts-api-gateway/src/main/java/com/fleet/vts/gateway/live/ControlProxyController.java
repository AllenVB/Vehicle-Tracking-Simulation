package com.fleet.vts.gateway.live;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Operator control, proxied to the simulator (which owns vehicle positions) so the
 * whole UI is served from — and talks to — this one origin. No CORS, one service.
 *
 * <p>It also fixes an identifier trap: the simulator addresses vehicles by their
 * device number (= the plate/IMEI number), while the rest of the system uses the
 * database {@code vehicle.id}. Those are NOT the same — vehicle rows are seeded in a
 * hashed order to scatter the vehicle types, so identity ids do not line up with
 * plate numbers. Callers here always speak {@code vehicleId}; the translation to the
 * simulator's index happens below, via the device IMEI (a natural key).
 */
@RestController
@RequestMapping("/api/v1/control")
public class ControlProxyController {

    private static final long MAPPING_TTL_MILLIS = Duration.ofMinutes(5).toMillis();

    private final JdbcTemplate jdbc;
    private final RestClient simulator;

    // The fleet is static, so a lazily loaded snapshot with a TTL is enough here.
    private volatile Map<Long, Integer> toSimIndex = Map.of();
    private volatile Map<Integer, Long> toVehicleId = Map.of();
    private volatile long loadedAt;

    public ControlProxyController(JdbcTemplate jdbc, RestClient simulatorRestClient) {
        this.jdbc = jdbc;
        this.simulator = simulatorRestClient;
    }

    private synchronized void refreshMappingIfStale() {
        if (!toSimIndex.isEmpty() && System.currentTimeMillis() - loadedAt < MAPPING_TTL_MILLIS) {
            return;
        }
        Map<Long, Integer> forward = new HashMap<>();
        Map<Integer, Long> reverse = new HashMap<>();
        jdbc.query("SELECT v.id AS vehicle_id, d.imei FROM vehicle v JOIN device d ON d.vehicle_id = v.id",
                rs -> {
                    long vehicleId = rs.getLong("vehicle_id");
                    int index = Integer.parseInt(rs.getString("imei").trim());
                    forward.put(vehicleId, index);
                    reverse.put(index, vehicleId);
                });
        this.toSimIndex = forward;
        this.toVehicleId = reverse;
        this.loadedAt = System.currentTimeMillis();
    }

    public record MoveRequest(double lat, double lon) {
    }

    public record DestRequest(String province) {
    }

    /** Province names for the operator's "create route" destination picker. */
    @GetMapping("/provinces")
    public ResponseEntity<Object> provinces() {
        Object list = simulator.get().uri("/api/control/provinces").retrieve().body(Object.class);
        return ResponseEntity.ok(list);
    }

    /** Dispatch a vehicle on a fresh route to the operator-chosen province. */
    @PostMapping("/{vehicleId}/destination")
    public ResponseEntity<Void> destination(@PathVariable Long vehicleId, @RequestBody DestRequest request) {
        Integer index = simIndex(vehicleId);
        if (index == null) {
            return ResponseEntity.notFound().build();
        }
        simulator.post().uri("/api/control/{i}/destination", index)
                .body(request).retrieve().toBodilessEntity();
        return ResponseEntity.ok().build();
    }

    /**
     * Dispatch state per vehicle: where it is heading, how much real road distance is
     * left, and whether the operator is holding it in place. Keyed by {@code vehicleId},
     * never by the simulator's index.
     */
    @GetMapping("/state")
    public List<Map<String, Object>> state() {
        refreshMappingIfStale();
        List<Map<String, Object>> fromSim = simulator.get().uri("/api/positions")
                .retrieve().body(List.class);
        Map<Integer, Long> byIndex = toVehicleId;

        List<Map<String, Object>> out = new ArrayList<>();
        if (fromSim == null) {
            return out;
        }
        for (Map<String, Object> p : fromSim) {
            Integer index = ((Number) p.get("id")).intValue();
            Long vehicleId = byIndex.get(index);
            if (vehicleId == null) {
                continue;
            }
            Map<String, Object> m = new HashMap<>();
            m.put("vehicleId", vehicleId);
            m.put("destination", p.get("destination"));
            m.put("destLat", p.get("destLat"));
            m.put("destLon", p.get("destLon"));
            m.put("remainingKm", p.get("remainingKm"));
            m.put("etaMinutes", p.get("etaMinutes"));
            m.put("parked", p.get("parked"));
            m.put("flying", p.get("flying"));
            out.add(m);
        }
        return out;
    }

    @PostMapping("/{vehicleId}/position")
    public ResponseEntity<Map<String, Object>> move(@PathVariable Long vehicleId,
                                                    @RequestBody MoveRequest request) {
        Integer index = simIndex(vehicleId);
        if (index == null) {
            return ResponseEntity.notFound().build();
        }
        // Carry the simulator's outcome back (moved? refused, and how far off-road?) so the
        // UI can either place the marker where the vehicle landed or explain the refusal.
        @SuppressWarnings("unchecked")
        Map<String, Object> result = simulator.post().uri("/api/control/{i}/position", index)
                .body(request).retrieve().body(Map.class);
        return ResponseEntity.ok(result);
    }

    /** The route a vehicle will take (current position -> destination), for the UI to draw. */
    @GetMapping("/{vehicleId}/route")
    public ResponseEntity<Object> route(@PathVariable Long vehicleId) {
        Integer index = simIndex(vehicleId);
        if (index == null) {
            return ResponseEntity.notFound().build();
        }
        Object geometry = simulator.get().uri("/api/control/{i}/route", index)
                .retrieve().body(Object.class);
        return ResponseEntity.ok(geometry);
    }


    /** The simulator's index for a vehicle is its IMEI as a number (imei = %015d(index)). */
    private Integer simIndex(Long vehicleId) {
        refreshMappingIfStale();
        return toSimIndex.get(vehicleId);
    }
}
