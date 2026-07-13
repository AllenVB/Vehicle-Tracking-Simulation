package com.fleet.vts.notification.service;

import com.fleet.vts.common.enums.Severity;
import com.fleet.vts.common.event.GeofenceEvent;
import com.fleet.vts.common.event.ViolationEvent;
import com.fleet.vts.notification.persistence.NotificationRepository;
import com.fleet.vts.notification.sender.NotificationMessage;
import com.fleet.vts.notification.sender.NotificationSender;
import com.fleet.vts.notification.sender.NotificationSenderRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

/**
 * Applies preferences, quiet hours and cooldown, then dispatches each resulting
 * notification through the matching channel strategy and records the outcome.
 */
@Service
public class NotificationService {

    private final CooldownService cooldown;
    private final RuleCooldownService ruleCooldown;
    private final PreferenceService preferences;
    private final NotificationSenderRegistry senders;
    private final NotificationRepository repository;
    private final Clock clock;
    private final Counter sent;
    private final Counter suppressed;

    public NotificationService(CooldownService cooldown, RuleCooldownService ruleCooldown,
                               PreferenceService preferences, NotificationSenderRegistry senders,
                               NotificationRepository repository, Clock clock,
                               MeterRegistry registry) {
        this.cooldown = cooldown;
        this.ruleCooldown = ruleCooldown;
        this.preferences = preferences;
        this.senders = senders;
        this.repository = repository;
        this.clock = clock;
        this.sent = Counter.builder("notification.sent").register(registry);
        this.suppressed = Counter.builder("notification.suppressed").register(registry);
    }

    public void onViolation(ViolationEvent v) {
        String title = v.ruleCode() + " ihlali";
        String body = "Araç " + v.vehicleId() + " kural " + v.ruleCode()
                + (v.value() != null ? " (ölçüm: " + v.value() + ")" : "");
        dispatch(v.tenantId(), v.vehicleId(), v.driverId(), v.ruleCode(), v.severity(),
                title, body, null, v.occurredAt());
    }

    public void onGeofence(GeofenceEvent g) {
        String ruleCode = "GEOFENCE_" + g.eventType().name();
        Severity severity = g.eventType().name().equals("ENTER") ? Severity.HIGH : Severity.MEDIUM;
        String title = "Geofence: " + g.geofenceName();
        String body = "Araç " + g.vehicleId() + " bölge " + g.eventType() + ": " + g.geofenceName();
        dispatch(g.tenantId(), g.vehicleId(), g.driverId(), ruleCode, severity,
                title, body, null, g.occurredAt());
    }

    private void dispatch(Long tenantId, Long vehicleId, Long driverId, String ruleCode,
                          Severity severity, String title, String body,
                          Long sourceViolationId, Instant occurredAt) {
        int cooldownSeconds = ruleCooldown.cooldownSeconds(tenantId, ruleCode);
        if (!cooldown.tryAcquire(tenantId, vehicleId, ruleCode, cooldownSeconds)) {
            return; // still within cooldown window
        }
        List<Preference> prefs = preferences.preferencesFor(tenantId, ruleCode);
        LocalTime now = LocalTime.now(clock);
        for (Preference p : prefs) {
            NotificationMessage msg = new NotificationMessage(tenantId, p.userId(), driverId,
                    vehicleId, ruleCode, severity, p.channel(), title, body, sourceViolationId, occurredAt);
            if (p.isQuiet(now)) {
                repository.insertNotification(msg, "SUPPRESSED");
                suppressed.increment();
                continue;
            }
            NotificationSender sender = senders.forChannel(p.channel());
            boolean ok = sender != null && sender.send(msg);
            Long id = repository.insertNotification(msg, ok ? "SENT" : "FAILED");
            repository.insertAttempt(id, p.channel().name(), ok, ok ? null : "sender unavailable");
            if (ok) {
                sent.increment();
            }
        }
    }
}
