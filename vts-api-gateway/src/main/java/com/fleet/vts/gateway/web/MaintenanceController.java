package com.fleet.vts.gateway.web;

import com.fleet.vts.gateway.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /** Sürücünün işaretleyebileceği durumlar (mobil uygulama üzerinden). */
    public static final Set<String> DRIVER_STATUSES = Set.of("PENDING", "IN_PROGRESS", "DONE");

    /**
     * Plan durumunu ilerletir; yalnızca aracın sürücüsü çağırır (bkz. DriverMaintenanceController,
     * araç sahipliği orada X-Device-Session ile doğrulanır). Admin işaretleme yetkisi yoktur.
     *
     * <p>{@code DONE} işaretlenince plan bir sonraki döngüye kaydırılır: yeni "vade", geç kalınsa
     * bile servis anındaki odometreden hesaplanır — aksi halde her gecikme bir sonraki aralığı
     * tam o kadar kısaltır ve program giderek sıkışırdı. Kaydırma sonrası vade ileri gittiği için
     * rozet "Yapıldı" kalır; araç yeniden vadesine ulaşınca kendiliğinden "Bekleniyor"a döner.
     */
    @Transactional
    public void applyStatus(long vehicleId, long planId, String status, String actor) {
        if ("DONE".equals(status)) {
            jdbc.update("""
                    UPDATE maintenance_plan mp
                       SET last_service_km = v.odometer_km,
                           last_service_at = now(),
                           next_due_km = CASE WHEN mp.interval_km IS NULL THEN NULL
                                              ELSE v.odometer_km + mp.interval_km END,
                           next_due_at = CASE WHEN mp.interval_days IS NULL THEN NULL
                                              ELSE now() + make_interval(days => mp.interval_days) END,
                           status = 'DONE',
                           updated_at = now()
                      FROM vehicle v
                     WHERE mp.id = ? AND mp.vehicle_id = ? AND v.id = mp.vehicle_id
                    """, planId, vehicleId);
            jdbc.update("""
                    INSERT INTO maintenance_record (tenant_id, vehicle_id, plan_id, service_at, odometer_km, performed_by)
                    SELECT mp.tenant_id, mp.vehicle_id, mp.id, now(), v.odometer_km, ?
                    FROM maintenance_plan mp JOIN vehicle v ON v.id = mp.vehicle_id
                    WHERE mp.id = ?
                    """, actor, planId);
        } else {
            jdbc.update("""
                    UPDATE maintenance_plan
                       SET status = ?, updated_at = now()
                     WHERE id = ? AND vehicle_id = ?
                    """, status, planId, vehicleId);
        }
    }

    /**
     * Depolanan durum + vade birleşiminden rozetin göstereceği durumu üretir. DONE olup araç
     * yeniden vadesine ulaştıysa yeni döngü başlamış demektir → PENDING gösterilir.
     */
    public static String effectiveStatus(String raw, Long nextDueKm, OffsetDateTime nextDueAt, Long odometerKm) {
        String s = raw == null ? "PENDING" : raw;
        if ("DONE".equals(s) && isDue(nextDueKm, nextDueAt, odometerKm)) {
            return "PENDING";
        }
        return s;
    }

    private static boolean isDue(Long nextDueKm, OffsetDateTime nextDueAt, Long odometerKm) {
        boolean byKm = nextDueKm != null && odometerKm != null && odometerKm >= nextDueKm;
        boolean byDate = nextDueAt != null && !nextDueAt.toInstant().isAfter(java.time.Instant.now());
        return byKm || byDate;
    }

    /** Tenant'ın tüm aktif bakım planlarını araç plakasıyla birlikte listeler. */
    @GetMapping("/plans")
    public List<Map<String, Object>> plans(@AuthenticationPrincipal Jwt jwt) {
        long tenant = CurrentUser.tenantId(jwt);
        return jdbc.query("""
                        SELECT mp.id, mp.name, mp.vehicle_id, v.plate, v.odometer_km,
                               mp.interval_km, mp.interval_days,
                               mp.next_due_km, mp.next_due_at,
                               mp.last_service_km, mp.last_service_at, mp.status
                        FROM maintenance_plan mp
                        JOIN vehicle v ON v.id = mp.vehicle_id
                        WHERE mp.tenant_id = ? AND mp.enabled
                        ORDER BY mp.vehicle_id, mp.id
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
                        row.put("intervalKm", rs.getObject("interval_km", Integer.class));
                        row.put("intervalDays", rs.getObject("interval_days", Integer.class));
                        Long nextDueKm = rs.getObject("next_due_km", Long.class);
                        row.put("nextDueKm", nextDueKm);
                        OffsetDateTime dueAt = rs.getObject("next_due_at", OffsetDateTime.class);
                        row.put("nextDueAt", dueAt == null ? null : dueAt.toInstant());
                        OffsetDateTime svcAt = rs.getObject("last_service_at", OffsetDateTime.class);
                        row.put("lastServiceAt", svcAt == null ? null : svcAt.toInstant());
                        boolean overdue = isOverdue(nextDueKm, dueAt);
                        row.put("overdue", overdue);
                        // Durumu sürücü işaretler; admin yalnızca görür. DONE olup vadesi tekrar
                        // gelmişse (bir sonraki döngü) rozeti otomatik "Bekleniyor"a çevir.
                        String raw = rs.getString("status");
                        Long odo = rs.getObject("odometer_km", Long.class);
                        row.put("status", effectiveStatus(raw, nextDueKm, dueAt, odo));
                        out.add(row);
                    }
                    return out;
                },
                tenant);
    }

    public record PlanRequest(Long vehicleId, String name, Long intervalKm, Integer intervalDays) {}

    /** Yeni bakım planı oluşturur; başlangıç noktası aracın mevcut odometre değeridir. */
    @PostMapping("/plans")
    @PreAuthorize("hasAnyRole('ADMIN', 'FLEET_MANAGER')")
    @Transactional
    public ResponseEntity<Map<String, Object>> createPlan(@AuthenticationPrincipal Jwt jwt,
                                                          @RequestBody PlanRequest req) {
        long tenant = CurrentUser.tenantId(jwt);
        if (req.vehicleId() == null || req.name() == null || req.name().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "vehicleId ve name gerekli"));
        }
        Long odometer;
        try {
            odometer = jdbc.queryForObject(
                    "SELECT odometer_km FROM vehicle WHERE id = ? AND tenant_id = ?",
                    Long.class, req.vehicleId(), tenant);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
        if (odometer == null) odometer = 0L;

        Long nextDueKm = req.intervalKm() != null ? odometer + req.intervalKm() : null;
        OffsetDateTime nextDueAt = req.intervalDays() != null
                ? OffsetDateTime.now().plusDays(req.intervalDays()) : null;

        Long id = jdbc.queryForObject("""
                INSERT INTO maintenance_plan
                    (tenant_id, vehicle_id, name, interval_km, interval_days,
                     last_service_km, last_service_at, next_due_km, next_due_at, enabled)
                VALUES (?, ?, ?, ?, ?, ?, now(), ?, ?, true)
                RETURNING id
                """, Long.class, tenant, req.vehicleId(), req.name().trim(),
                req.intervalKm(), req.intervalDays(), odometer, nextDueKm, nextDueAt);

        return ResponseEntity.status(201).body(Map.of("planId", id));
    }

    private static boolean isOverdue(Long remainingKm, OffsetDateTime dueAt) {
        return (remainingKm != null && remainingKm <= 0)
                || (dueAt != null && dueAt.toInstant().isBefore(java.time.Instant.now()));
    }
}
