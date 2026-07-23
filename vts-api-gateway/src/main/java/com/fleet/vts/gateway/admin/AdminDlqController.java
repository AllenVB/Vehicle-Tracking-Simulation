package com.fleet.vts.gateway.admin;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Dead-letter operations, ADMIN only — replay moves data back onto live topics, which is not
 * something a routine operator role should be able to trigger.
 */
@RestController
@RequestMapping("/api/v1/admin/dlq")
@PreAuthorize("hasRole('ADMIN')")
public class AdminDlqController {

    private final DlqService dlq;

    public AdminDlqController(DlqService dlq) {
        this.dlq = dlq;
    }

    /** Depth of every DLQ — the "is anything stuck?" view. */
    @GetMapping
    public Map<String, Long> depths() {
        return dlq.depths();
    }

    /**
     * Replay up to {@code max} messages from one DLQ back into the pipeline. Framework DLQs are
     * republished to their source topic; the telemetry DLQ is re-ingested.
     */
    @PostMapping("/{topic}/replay")
    public Map<String, Object> replay(@PathVariable String topic,
                                      @RequestParam(defaultValue = "500") int max) {
        return dlq.replay(topic, max);
    }
}
