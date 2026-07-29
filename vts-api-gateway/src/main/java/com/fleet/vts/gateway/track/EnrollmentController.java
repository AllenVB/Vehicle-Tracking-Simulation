package com.fleet.vts.gateway.track;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Phone self-enrollment: after the pairing code is confirmed, the phone posts its own plate and
 * model and gets back the IMEI to stream under. Gated on a CONFIRMED pairing session so an
 * unpaired phone cannot create fleet rows.
 */
@RestController
@RequestMapping("/api/v1/track")
public class EnrollmentController {

    private final EnrollmentService enrollment;
    private final PairingService pairing;

    public EnrollmentController(EnrollmentService enrollment, PairingService pairing) {
        this.enrollment = enrollment;
        this.pairing = pairing;
    }

    public record EnrollRequest(String publicId, String deviceKey, String plate, String make, String model) {
    }

    /** This phone's existing vehicle (so the tracker prefills and reuses it). 404 if new phone. */
    @GetMapping("/device/{deviceKey}")
    public ResponseEntity<?> device(@PathVariable String deviceKey) {
        return enrollment.findByDeviceKey(deviceKey)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/enroll")
    public ResponseEntity<?> enroll(@RequestBody EnrollRequest req) {
        if (req.publicId() == null || !pairing.isConfirmed(req.publicId())) {
            return ResponseEntity.status(403).body(Map.of("error", "NOT_CONFIRMED"));
        }
        if (isBlank(req.deviceKey()) || isBlank(req.plate())) {
            return ResponseEntity.badRequest().body(Map.of("error", "MISSING_FIELDS"));
        }
        try {
            return ResponseEntity.ok(enrollment.enroll(
                    req.deviceKey().trim(), req.plate().trim(), trimOrNull(req.make()), trimOrNull(req.model())));
        } catch (DataIntegrityViolationException e) {
            // (tenant, plate) is unique: this plate already belongs to another vehicle.
            return ResponseEntity.status(409).body(Map.of("error", "PLATE_TAKEN"));
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String trimOrNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
