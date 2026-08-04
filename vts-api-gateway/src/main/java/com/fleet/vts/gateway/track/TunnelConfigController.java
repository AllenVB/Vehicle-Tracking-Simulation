package com.fleet.vts.gateway.track;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Public tunnel-URL config for the driver-app QR. The startup script discovers a public HTTPS
 * tunnel at runtime and POSTs it here; the operator page reads it back to build the single QR that
 * opens the driver page over HTTPS — browsers only expose geolocation in a secure context, so a
 * bare LAN address will not do. In-memory and last-write-wins: the URL only lives as long as the
 * tunnel does.
 */
@RestController
@RequestMapping("/api/v1/track")
public class TunnelConfigController {

    private volatile String publicUrl = "";

    @GetMapping("/config")
    public Map<String, String> config() {
        return Map.of("publicUrl", publicUrl);
    }

    @PostMapping("/config")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setConfig(@RequestBody Map<String, String> body) {
        String url = body == null ? null : body.get("url");
        this.publicUrl = url == null ? "" : url.trim();
    }
}
