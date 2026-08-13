package com.fleet.vts.gateway.track;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Sefer öncesi araç kontrolü (DVIR) — sürücü tarafı (public, {@code /api/v1/track}). Sürücü
 * kontrol listesini doldurur; kimlik {@code X-Device-Session} ile çözülür, kayıt yalnızca
 * tuttuğu araca yazılır. Herhangi bir madde "defect" ise genel sonuç DEFECT olur.
 */
@RestController
@RequestMapping("/api/v1/track")
public class DriverInspectionController {

    /** Kabul edilen kontrol maddeleri (istemci uydurmasın). */
    private static final Set<String> ITEMS = Set.of("tires", "brakes", "lights", "fluids", "body", "horn");
    private static final Set<String> VALUES = Set.of("ok", "defect");

    private final DriverSessionService sessions;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public DriverInspectionController(DriverSessionService sessions, JdbcTemplate jdbc, ObjectMapper json) {
        this.sessions = sessions;
        this.jdbc = jdbc;
        this.json = json;
    }

    public record InspectionRequest(Map<String, String> items, String note) {
    }

    @PostMapping("/inspection")
    public ResponseEntity<?> submit(@RequestBody InspectionRequest req,
                                    @RequestHeader(value = "X-Device-Session", required = false) String session) {
        Optional<DriverSessionService.Identity> id = sessions.identify(session);
        if (id.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "NO_SESSION"));
        }
        if (req == null || req.items() == null || req.items().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "items gerekli"));
        }

        // Yalnızca bilinen madde/değerleri al; genel sonucu buradan hesapla.
        Map<String, String> clean = new LinkedHashMap<>();
        boolean defect = false;
        for (Map.Entry<String, String> e : req.items().entrySet()) {
            String k = e.getKey(), v = e.getValue() == null ? "" : e.getValue().toLowerCase();
            if (ITEMS.contains(k) && VALUES.contains(v)) {
                clean.put(k, v);
                if ("defect".equals(v)) defect = true;
            }
        }
        if (clean.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "geçerli madde yok"));
        }

        long vehicleId = id.get().vehicleId();
        Long tenant = jdbc.queryForObject("SELECT tenant_id FROM vehicle WHERE id = ?", Long.class, vehicleId);
        String itemsJson;
        try {
            itemsJson = json.writeValueAsString(clean);
        } catch (Exception ex) {
            return ResponseEntity.internalServerError().body(Map.of("error", "SERIALIZE"));
        }
        String note = req.note() == null ? null : req.note().trim();
        if (note != null && note.length() > 500) note = note.substring(0, 500);

        jdbc.update("""
                INSERT INTO vehicle_inspection (tenant_id, vehicle_id, overall, items, note)
                VALUES (?, ?, ?, ?::jsonb, ?)
                """, tenant, vehicleId, defect ? "DEFECT" : "OK", itemsJson, note);

        return ResponseEntity.status(201).body(Map.of("overall", defect ? "DEFECT" : "OK"));
    }
}
