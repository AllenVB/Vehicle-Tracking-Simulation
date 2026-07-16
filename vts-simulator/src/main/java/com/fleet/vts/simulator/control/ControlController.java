package com.fleet.vts.simulator.control;

import com.fleet.vts.simulator.sim.FleetSimulator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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

    @GetMapping("/positions")
    public List<Map<String, Object>> positions() {
        return simulator.positions();
    }

    /** The route a vehicle will take (current position -> destination), as [[lat, lon], ...]. */
    @GetMapping("/control/{id}/route")
    public List<double[]> route(@PathVariable long id) {
        return simulator.routeGeometry(id);
    }

    @PostMapping("/control/{id}/position")
    public ResponseEntity<Map<String, Object>> move(@PathVariable long id, @RequestBody MoveRequest request) {
        Map<String, Object> result = simulator.setManualPosition(id, request.lat(), request.lon());
        return result == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(result);
    }

    @DeleteMapping("/control/{id}/position")
    public ResponseEntity<Void> release(@PathVariable long id) {
        return simulator.releaseVehicle(id)
                ? ResponseEntity.ok().build()
                : ResponseEntity.notFound().build();
    }
}
