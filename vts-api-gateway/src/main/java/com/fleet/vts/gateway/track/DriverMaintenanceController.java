package com.fleet.vts.gateway.track;

import com.fleet.vts.gateway.web.MaintenanceController;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Sürücünün kendi aracının bakım planlarını görüp durumunu işaretlediği uç (public,
 * {@code /api/v1/track} ailesi gibi). Telefon JWT taşımaz; kimlik {@code X-Device-Session}
 * ile çözülür, dolayısıyla bir sürücü yalnızca gerçekten tuttuğu aracın planlarına dokunabilir.
 *
 * <p>Admin planı tanımlar ama durumunu <em>değiştiremez</em>; "Bekleniyor / Bakımda / Yapıldı"
 * geçişleri yalnızca buradan, aracı kullanan sürücü tarafından yapılır. Admin paneli sonucu
 * salt-okunur bir rozet olarak gösterir.
 */
@RestController
@RequestMapping("/api/v1/track")
public class DriverMaintenanceController {

    private final DriverSessionService sessions;
    private final JdbcTemplate jdbc;
    private final MaintenanceController maintenance;

    public DriverMaintenanceController(DriverSessionService sessions, JdbcTemplate jdbc,
                                       MaintenanceController maintenance) {
        this.sessions = sessions;
        this.jdbc = jdbc;
        this.maintenance = maintenance;
    }

    /** Bu aracın aktif bakım planları: gösterilecek durum + vadesi gelmiş mi bilgisiyle. */
    @GetMapping("/maintenance")
    public ResponseEntity<List<Map<String, Object>>> list(
            @RequestHeader(value = "X-Device-Session", required = false) String session) {
        Optional<DriverSessionService.Identity> id = sessions.identify(session);
        if (id.isEmpty()) {
            return ResponseEntity.status(401).build();
        }
        long vehicleId = id.get().vehicleId();

        List<Map<String, Object>> rows = jdbc.query("""
                        SELECT mp.id, mp.name, v.odometer_km,
                               mp.interval_km, mp.interval_days,
                               mp.next_due_km, mp.next_due_at, mp.last_service_at, mp.status
                        FROM maintenance_plan mp
                        JOIN vehicle v ON v.id = mp.vehicle_id
                        WHERE mp.vehicle_id = ? AND mp.enabled
                        ORDER BY mp.id
                        """,
                rs -> {
                    List<Map<String, Object>> out = new ArrayList<>();
                    while (rs.next()) {
                        Long nextDueKm = rs.getObject("next_due_km", Long.class);
                        OffsetDateTime dueAt = rs.getObject("next_due_at", OffsetDateTime.class);
                        Long odo = rs.getObject("odometer_km", Long.class);
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("planId", rs.getLong("id"));
                        row.put("name", rs.getString("name"));
                        row.put("odometerKm", odo);
                        row.put("nextDueKm", nextDueKm);
                        row.put("nextDueAt", dueAt == null ? null : dueAt.toInstant());
                        row.put("status", MaintenanceController.effectiveStatus(
                                rs.getString("status"), nextDueKm, dueAt, odo));
                        row.put("due", due(nextDueKm, dueAt, odo));
                        out.add(row);
                    }
                    return out;
                },
                vehicleId);
        return ResponseEntity.ok(rows);
    }

    public record StatusRequest(String status) {
    }

    /** Sürücü plan durumunu işaretler. Plan bu araca ait değilse 404. */
    @PostMapping("/maintenance/{planId}/status")
    public ResponseEntity<?> setStatus(@PathVariable Long planId,
                                       @RequestBody StatusRequest req,
                                       @RequestHeader(value = "X-Device-Session", required = false) String session) {
        Optional<DriverSessionService.Identity> id = sessions.identify(session);
        if (id.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "NO_SESSION"));
        }
        String status = req == null || req.status() == null ? "" : req.status().trim().toUpperCase();
        if (!MaintenanceController.DRIVER_STATUSES.contains(status)) {
            return ResponseEntity.badRequest().body(Map.of("error", "BAD_STATUS"));
        }
        long vehicleId = id.get().vehicleId();

        // Plan gerçekten bu araca mı ait? (Başka aracın planını işaretlemeyi engelle.)
        Integer owns = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_plan WHERE id = ? AND vehicle_id = ? AND enabled",
                Integer.class, planId, vehicleId);
        if (owns == null || owns == 0) {
            return ResponseEntity.notFound().build();
        }

        maintenance.applyStatus(vehicleId, planId, status, "driver");
        return ResponseEntity.ok(Map.of("planId", planId, "status", status));
    }

    private static boolean due(Long nextDueKm, OffsetDateTime nextDueAt, Long odometerKm) {
        boolean byKm = nextDueKm != null && odometerKm != null && odometerKm >= nextDueKm;
        boolean byDate = nextDueAt != null && !nextDueAt.toInstant().isAfter(java.time.Instant.now());
        return byKm || byDate;
    }
}
