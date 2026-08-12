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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Görev atama (admin tarafı): bir araca hedef (varış noktası) atar, aktif görevleri canlı
 * konum + ETA ile listeler, iptal eder. Durumu <em>sürücü</em> ilerletir
 * (bkz. DriverJobController); admin atar, iptal eder ve izler.
 *
 * <p>Bir araç için aynı anda tek aktif görev tutulur: yeni atama, o aracın önceki aktif
 * görevini iptal eder — böylece hem sürücü hem müşteri linki tek net hedef görür.
 *
 * <p>Not: {@code /api/v1/dispatch} zaten "en yakın araç" için kullanılıyor; bu yüzden bu özellik
 * {@code /api/v1/jobs} altındadır.
 */
@RestController
@RequestMapping("/api/v1/jobs")
public class JobController {

    /** Sürücünün ilerletebileceği durumlar (admin yalnızca ASSIGNED üretir / iptal eder). */
    public static final Set<String> DRIVER_STATUSES = Set.of("EN_ROUTE", "ARRIVED", "DONE");
    static final String ACTIVE = "('ASSIGNED', 'EN_ROUTE', 'ARRIVED')";

    private final JdbcTemplate jdbc;
    private final PositionReader positions;

    public JobController(JdbcTemplate jdbc, PositionReader positions) {
        this.jdbc = jdbc;
        this.positions = positions;
    }

    public record CreateRequest(Long vehicleId, String title, String destLabel,
                                Double destLat, Double destLon) {
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'FLEET_MANAGER')")
    @Transactional
    public ResponseEntity<Map<String, Object>> create(@AuthenticationPrincipal Jwt jwt,
                                                       @RequestBody CreateRequest req) {
        long tenant = CurrentUser.tenantId(jwt);
        if (req.vehicleId() == null || req.title() == null || req.title().isBlank()
                || req.destLat() == null || req.destLon() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "vehicleId, title ve hedef konum gerekli"));
        }
        if (Math.abs(req.destLat()) > 90 || Math.abs(req.destLon()) > 180) {
            return ResponseEntity.badRequest().body(Map.of("error", "Geçersiz hedef konum"));
        }
        Integer owns = jdbc.queryForObject(
                "SELECT COUNT(*) FROM vehicle WHERE id = ? AND tenant_id = ?",
                Integer.class, req.vehicleId(), tenant);
        if (owns == null || owns == 0) {
            return ResponseEntity.notFound().build();
        }

        // Tek aktif görev: önceki aktifleri iptal et.
        jdbc.update("UPDATE dispatch_job SET status = 'CANCELLED', updated_at = now() "
                + "WHERE vehicle_id = ? AND tenant_id = ? AND status IN " + ACTIVE,
                req.vehicleId(), tenant);

        String title = req.title().trim();
        if (title.length() > 200) title = title.substring(0, 200);
        String label = req.destLabel() == null ? null : req.destLabel().trim();

        Long id = jdbc.queryForObject("""
                INSERT INTO dispatch_job (tenant_id, vehicle_id, title, dest_label, dest_lat, dest_lon, status)
                VALUES (?, ?, ?, ?, ?, ?, 'ASSIGNED')
                RETURNING id
                """, Long.class, tenant, req.vehicleId(), title, label, req.destLat(), req.destLon());

        return ResponseEntity.status(201).body(Map.of("jobId", id));
    }

    /** Tenant'ın aktif görevleri: plaka + anlık konum + kalan mesafe/ETA ile. */
    @GetMapping
    public List<Map<String, Object>> list(@AuthenticationPrincipal Jwt jwt) {
        long tenant = CurrentUser.tenantId(jwt);
        List<Map<String, Object>> rows = jdbc.query("""
                        SELECT dj.id, dj.vehicle_id, v.plate, dj.title, dj.dest_label,
                               dj.dest_lat, dj.dest_lon, dj.status, dj.created_at
                        FROM dispatch_job dj
                        JOIN vehicle v ON v.id = dj.vehicle_id
                        WHERE dj.tenant_id = ? AND dj.status IN """ + ACTIVE + """
                        ORDER BY dj.created_at DESC
                        """,
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    long vehicleId = rs.getLong("vehicle_id");
                    m.put("jobId", rs.getLong("id"));
                    m.put("vehicleId", vehicleId);
                    m.put("plate", rs.getString("plate"));
                    m.put("title", rs.getString("title"));
                    m.put("destLabel", rs.getString("dest_label"));
                    double dlat = rs.getDouble("dest_lat"), dlon = rs.getDouble("dest_lon");
                    m.put("destLat", dlat);
                    m.put("destLon", dlon);
                    m.put("status", rs.getString("status"));
                    OffsetDateTime created = rs.getObject("created_at", OffsetDateTime.class);
                    m.put("createdAt", created == null ? null : created.toInstant());
                    enrichEta(m, vehicleId, dlat, dlon);
                    return m;
                }, tenant);
        return new ArrayList<>(rows);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN', 'FLEET_MANAGER')")
    public ResponseEntity<Void> cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        int n = jdbc.update("UPDATE dispatch_job SET status = 'CANCELLED', updated_at = now() "
                + "WHERE id = ? AND tenant_id = ? AND status IN " + ACTIVE,
                id, CurrentUser.tenantId(jwt));
        return n == 0 ? ResponseEntity.notFound().build() : ResponseEntity.noContent().build();
    }

    /** Anlık konumdan hedefe kalan mesafe (km) ve ETA (dk) ekler; konum yoksa null bırakır. */
    private void enrichEta(Map<String, Object> m, long vehicleId, double destLat, double destLon) {
        PositionReader.Pos p = positions.read(vehicleId);
        if (p.known()) {
            double km = GeoEta.haversineKm(p.lat(), p.lon(), destLat, destLon);
            m.put("remainingKm", Math.round(km * 10) / 10.0);
            m.put("etaMin", GeoEta.etaMinutes(km, p.speedKmh()));
        } else {
            m.put("remainingKm", null);
            m.put("etaMin", null);
        }
    }
}
