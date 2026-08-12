package com.fleet.vts.gateway.track;

import com.fleet.vts.gateway.web.GeoEta;
import com.fleet.vts.gateway.web.JobController;
import com.fleet.vts.gateway.web.PositionReader;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Sürücünün kendi aracına atanmış görevi görüp durumunu ilerlettiği uç (public,
 * {@code /api/v1/track} ailesi). Kimlik {@code X-Device-Session} ile çözülür — sürücü
 * yalnızca tuttuğu aracın görevine dokunabilir. Admin görevi atar/iptal eder; sürücü
 * "Yola çıktım / Vardım / Tamamlandı" akışını yürütür.
 */
@RestController
@RequestMapping("/api/v1/track")
public class DriverJobController {

    private final DriverSessionService sessions;
    private final JdbcTemplate jdbc;
    private final PositionReader positions;

    public DriverJobController(DriverSessionService sessions, JdbcTemplate jdbc,
                               PositionReader positions) {
        this.sessions = sessions;
        this.jdbc = jdbc;
        this.positions = positions;
    }

    /** Bu aracın aktif görevi (varsa) hedef + kalan mesafe/ETA ile; yoksa boş gövde. */
    @GetMapping("/job")
    public ResponseEntity<Map<String, Object>> current(
            @RequestHeader(value = "X-Device-Session", required = false) String session) {
        Optional<DriverSessionService.Identity> id = sessions.identify(session);
        if (id.isEmpty()) {
            return ResponseEntity.status(401).build();
        }
        long vehicleId = id.get().vehicleId();
        Map<String, Object> job;
        try {
            job = jdbc.queryForMap("""
                    SELECT id, title, dest_label, dest_lat, dest_lon, status
                    FROM dispatch_job
                    WHERE vehicle_id = ? AND status IN ('ASSIGNED', 'EN_ROUTE', 'ARRIVED')
                    ORDER BY created_at DESC
                    LIMIT 1
                    """, vehicleId);
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of());   // aktif görev yok
        }

        double dlat = ((Number) job.get("dest_lat")).doubleValue();
        double dlon = ((Number) job.get("dest_lon")).doubleValue();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jobId", ((Number) job.get("id")).longValue());
        out.put("title", job.get("title"));
        out.put("destLabel", job.get("dest_label"));
        out.put("destLat", dlat);
        out.put("destLon", dlon);
        out.put("status", job.get("status"));
        PositionReader.Pos p = positions.read(vehicleId);
        if (p.known()) {
            double km = GeoEta.haversineKm(p.lat(), p.lon(), dlat, dlon);
            out.put("remainingKm", Math.round(km * 10) / 10.0);
            out.put("etaMin", GeoEta.etaMinutes(km, p.speedKmh()));
        }
        return ResponseEntity.ok(out);
    }

    public record StatusRequest(String status) {
    }

    @PostMapping("/job/{id}/status")
    public ResponseEntity<?> setStatus(@PathVariable Long id, @RequestBody StatusRequest req,
                                       @RequestHeader(value = "X-Device-Session", required = false) String session) {
        Optional<DriverSessionService.Identity> ident = sessions.identify(session);
        if (ident.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "NO_SESSION"));
        }
        String status = req == null || req.status() == null ? "" : req.status().trim().toUpperCase();
        if (!JobController.DRIVER_STATUSES.contains(status)) {
            return ResponseEntity.badRequest().body(Map.of("error", "BAD_STATUS"));
        }
        long vehicleId = ident.get().vehicleId();
        int n = jdbc.update("""
                UPDATE dispatch_job SET status = ?, updated_at = now()
                 WHERE id = ? AND vehicle_id = ? AND status IN ('ASSIGNED', 'EN_ROUTE', 'ARRIVED')
                """, status, id, vehicleId);
        if (n == 0) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("jobId", id, "status", status));
    }
}
