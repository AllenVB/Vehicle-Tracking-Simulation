package com.fleet.vts.gateway.track;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST surface for the device-flow phone pairing (see {@link PairingService}) plus the public
 * tunnel URL the startup script discovers at runtime. All open by design: a field phone carries
 * no JWT, and the security is the human-entered 2-digit code, not a token.
 */
@RestController
@RequestMapping("/api/v1/track")
public class PairingController {

    private final PairingService pairing;

    public PairingController(PairingService pairing) {
        this.pairing = pairing;
    }

    /** Desktop starts a session; keeps {@code sessionId}, puts {@code publicId} in the QR. */
    @PostMapping("/pair")
    public Map<String, String> create() {
        PairingService.Session s = pairing.create();
        return Map.of("sessionId", s.sessionId(), "publicId", s.publicId());
    }

    /** Phone scanned the QR (mints the code, revealed to the desktop only). */
    @PostMapping("/pair/scan")
    public ResponseEntity<Map<String, Object>> scan(@RequestBody Map<String, String> body) {
        return pairing.scan(body.get("publicId"))
                .<ResponseEntity<Map<String, Object>>>map(s -> ResponseEntity.ok(Map.of("status", s.status().name())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Desktop polls for status; the code appears here once the phone has scanned. */
    @GetMapping("/pair/{sessionId}")
    public ResponseEntity<Map<String, Object>> status(@PathVariable String sessionId) {
        return pairing.bySessionId(sessionId)
                .map(s -> ResponseEntity.ok(s.desktopView()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Phone submits the code the human read off the desktop. */
    @PostMapping("/pair/verify")
    public Map<String, Object> verify(@RequestBody Map<String, String> body) {
        return Map.of("ok", pairing.verify(body.get("publicId"), body.get("code")));
    }

    @GetMapping("/config")
    public Map<String, String> config() {
        return Map.of("publicUrl", pairing.getPublicUrl());
    }

    @PostMapping("/config")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setConfig(@RequestBody Map<String, String> body) {
        pairing.setPublicUrl(body.get("url"));
    }
}
