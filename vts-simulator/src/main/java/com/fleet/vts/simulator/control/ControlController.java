package com.fleet.vts.simulator.control;

import com.fleet.vts.simulator.sim.FleetSimulator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Operator-console API (served alongside the console UI on the simulator port).
 * The simulator is the fleet's source of truth, so a manual override here flows
 * through the normal pipeline (ingestion -> processing -> gateway) and shows up
 * on the main live map within a second.
 */
@RestController
@RequestMapping("/api")
public class ControlController {

    private final FleetSimulator simulator;

    public ControlController(FleetSimulator simulator) {
        this.simulator = simulator;
    }

    public record MoveRequest(double lat, double lon) {
    }

    public record DestRequest(String province) {
    }

    @GetMapping("/positions")
    public List<Map<String, Object>> positions() {
        return simulator.positions();
    }

    /** Province names for the operator's destination picker. */
    @GetMapping("/control/provinces")
    public List<String> provinces() {
        return simulator.provinceNames();
    }

    /** Dispatch a vehicle on a fresh route to the chosen province. */
    @PostMapping("/control/{id}/destination")
    public ResponseEntity<Void> destination(@PathVariable long id, @RequestBody DestRequest request) {
        return simulator.dispatchTo(id, request.province())
                ? ResponseEntity.ok().build()
                : ResponseEntity.notFound().build();
    }

    /** The route a vehicle will take (current position -> destination), as [[lat, lon], ...]. */
    @GetMapping("/control/{id}/route")
    public List<double[]> route(@PathVariable long id) {
        return simulator.routeGeometry(id);
    }

    /**
     * Move a vehicle and let it drive on. The body reports {@code moved}: a land vehicle
     * clicked off-road is refused (with {@code reason} and {@code offRoadMeters}) rather
     * than quietly snapped somewhere the operator did not pick. 200 either way — the
     * request was understood, and the refusal is the answer.
     *
     * <p>{@code reason} is one of {@code OFF_ROAD}, {@code LOOP_VEHICLE} (the geofence
     * vehicle laps a fixed zone), {@code NO_ROUTE_FROM_HERE} or {@code ROUTING_UNAVAILABLE}.
     */
    @PostMapping("/control/{id}/position")
    public ResponseEntity<Map<String, Object>> move(@PathVariable long id, @RequestBody MoveRequest request) {
        Map<String, Object> result = simulator.moveVehicle(id, request.lat(), request.lon());
        return result == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(result);
    }
}
