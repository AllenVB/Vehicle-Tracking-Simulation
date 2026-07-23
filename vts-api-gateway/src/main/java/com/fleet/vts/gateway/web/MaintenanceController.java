package com.fleet.vts.gateway.web;

import com.fleet.vts.gateway.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maintenance that is actually due, and marking it done.
 *
 * <p>The plan and record tables have existed since V9 with nothing in them and no way to put
 * anything in them, so the nightly reminder job counted zero every night — a green job
 * measuring an empty table. Two things were missing and both are now in place: plans (V33) and
 * a vehicle odometer that moves (processing writes it from the device's own reading).
 *
 * <p>"Due" is deliberately two questions at once. A vehicle is due by distance or by date, and
 * a fleet needs the earlier of the two: a truck that covers 15 000 km in four months and a van
 * that covers it in three years both need servicing, for different reasons.
 */
@RestController
@RequestMapping("/api/v1/maintenance")
public class MaintenanceController {

    /** How far ahead "yaklaşan" reaches, unless the caller says otherwise. */
    private static final int DEFAULT_WITHIN_KM = 1_000;
    private static final int DEFAULT_WITHIN_DAYS = 30;
    private static final int MAX_ROWS = 200;

    private final JdbcTemplate jdbc;

    public MaintenanceController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Plans that are due or close to it, most overdue first.
     *
     * <p>{@code remainingKm} goes negative once a vehicle is past its service distance, which
     * is the number an operator actually wants — "2 300 km overdue" says more than a flag.
     */
    @GetMapping("/due")
    public List<Map<String, Object>> due(@AuthenticationPrincipal Jwt jwt,
                                         @RequestParam(defaultValue = "" + DEFAULT_WITHIN_KM) int withinKm,
                                         @RequestParam(defaultValue = "" + DEFAULT_WITHIN_DAYS) int withinDays) {
        long tenant = CurrentUser.tenantId(jwt);

        return jdbc.query("""
                        SELECT mp.id, mp.name, mp.vehicle_id, v.plate, v.odometer_km,
                               mp.next_due_km, mp.next_due_at,
                               mp.next_due_km - v.odometer_km AS remaining_km
                        FROM maintenance_plan mp
                        JOIN vehicle v ON v.id = mp.vehicle_id
                        WHERE mp.tenant_id = ? AND mp.enabled
                          AND ((mp.next_due_km IS NOT NULL AND v.odometer_km >= mp.next_due_km - ?)
                            OR (mp.next_due_at IS NOT NULL AND mp.next_due_at <= now() + make_interval(days => ?)))
                        ORDER BY
                          -- Overdue first, then nearest. COALESCE keeps a date-only plan from
                          -- sorting as if it had infinite distance left.
                          COALESCE(mp.next_due_km - v.odometer_km, 2147483647),
                          mp.next_due_at
                        LIMIT ?
                        """,
                rs -> {
                    List<Map<String, Object>> out = new ArrayList<>();
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("planId", rs.getLong("id"));
                        row.put("name", rs.getString("name"));
                        row.put("vehicleId", rs.getLong("vehicle_id"));
                        row.put("plate", rs.getString("plate"));
                        row.put("odometerKm", rs.getLong("odometer_km"));
                        row.put("nextDueKm", rs.getObject("next_due_km", Long.class));
                        row.put("remainingKm", rs.getObject("remaining_km", Long.class));
                        OffsetDateTime dueAt = rs.getObject("next_due_at", OffsetDateTime.class);
                        row.put("nextDueAt", dueAt == null ? null : dueAt.toInstant());
                        row.put("overdue", isOverdue(rs.getObject("remaining_km", Long.class), dueAt));
                        out.add(row);
                    }
                    return out;
                },
                tenant, withinKm, withinDays, MAX_ROWS);
    }

    /**
     * Per-vehicle progress toward the next km-based service, for the map popup.
     *
     * <p>Loaded in bulk, once, like the driver scores: the popup needs "1234 / 10000" for
     * whichever vehicle was clicked, and asking per vehicle would be one request per marker.
     * {@code sinceKm} is how far it has driven since its last service, {@code intervalKm} the
     * distance between services — so the ratio reads exactly as the operator expects.
     */
    @GetMapping("/progress")
    public List<Map<String, Object>> progress(@AuthenticationPrincipal Jwt jwt) {
        long tenant = CurrentUser.tenantId(jwt);
        return jdbc.query("""
                        SELECT mp.vehicle_id,
                               GREATEST(0, v.odometer_km - mp.last_service_km) AS since_km,
                               mp.interval_km,
                               (v.odometer_km >= mp.next_due_km) AS overdue
                        FROM maintenance_plan mp
                        JOIN vehicle v ON v.id = mp.vehicle_id
                        WHERE mp.tenant_id = ? AND mp.enabled AND mp.interval_km IS NOT NULL
                        """,
                rs -> {
                    List<Map<String, Object>> out = new ArrayList<>();
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("vehicleId", rs.getLong("vehicle_id"));
                        row.put("sinceKm", rs.getLong("since_km"));
                        row.put("intervalKm", rs.getLong("interval_km"));
                        row.put("overdue", rs.getBoolean("overdue"));
                        out.add(row);
                    }
                    return out;
                },
                tenant);
    }

    /**
     * Record that a plan was serviced and roll it forward.
     *
     * <p>The next due point is computed from the odometer reading at service time, not from the
     * previous due point. Otherwise a service done 3 000 km late would shorten the next
     * interval by exactly that much, and the schedule would drift tighter every cycle.
     */
    @PostMapping("/{planId}/serviced")
    @PreAuthorize("hasAnyRole('ADMIN', 'FLEET_MANAGER')")
    @Transactional
    public ResponseEntity<Map<String, Object>> markServiced(@AuthenticationPrincipal Jwt jwt,
                                                            @PathVariable Long planId) {
        long tenant = CurrentUser.tenantId(jwt);

        int rolled = jdbc.update("""
                UPDATE maintenance_plan mp
                   SET last_service_km = v.odometer_km,
                       last_service_at = now(),
                       next_due_km = CASE WHEN mp.interval_km IS NULL THEN NULL
                                          ELSE v.odometer_km + mp.interval_km END,
                       next_due_at = CASE WHEN mp.interval_days IS NULL THEN NULL
                                          ELSE now() + make_interval(days => mp.interval_days) END,
                       updated_at = now()
                  FROM vehicle v
                 WHERE mp.id = ? AND mp.tenant_id = ? AND v.id = mp.vehicle_id
                """, planId, tenant);

        if (rolled == 0) {
            return ResponseEntity.notFound().build();
        }

        jdbc.update("""
                INSERT INTO maintenance_record (tenant_id, vehicle_id, plan_id, service_at, odometer_km, performed_by)
                SELECT mp.tenant_id, mp.vehicle_id, mp.id, now(), v.odometer_km, ?
                FROM maintenance_plan mp JOIN vehicle v ON v.id = mp.vehicle_id
                WHERE mp.id = ?
                """, jwt == null ? "system" : jwt.getSubject(), planId);

        Map<String, Object> after = jdbc.queryForMap(
                "SELECT next_due_km, next_due_at FROM maintenance_plan WHERE id = ?", planId);
        return ResponseEntity.ok(Map.of(
                "planId", planId,
                "nextDueKm", String.valueOf(after.get("next_due_km")),
                "nextDueAt", String.valueOf(after.get("next_due_at"))));
    }

    private static boolean isOverdue(Long remainingKm, OffsetDateTime dueAt) {
        return (remainingKm != null && remainingKm <= 0)
                || (dueAt != null && dueAt.toInstant().isBefore(java.time.Instant.now()));
    }
}
