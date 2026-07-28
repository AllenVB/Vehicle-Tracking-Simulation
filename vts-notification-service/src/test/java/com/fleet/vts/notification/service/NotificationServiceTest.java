package com.fleet.vts.notification.service;

import com.fleet.vts.common.enums.NotificationChannel;
import com.fleet.vts.common.enums.RuleType;
import com.fleet.vts.common.enums.Severity;
import com.fleet.vts.common.event.ViolationEvent;
import com.fleet.vts.notification.persistence.NotificationRepository;
import com.fleet.vts.notification.sender.NotificationSender;
import com.fleet.vts.notification.sender.NotificationSenderRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Cooldown, quiet-hours and channel routing with mocked collaborators. */
class NotificationServiceTest {

    private final CooldownService cooldown = mock(CooldownService.class);
    private final RuleCooldownService ruleCooldown = mock(RuleCooldownService.class);
    private final PreferenceService preferences = mock(PreferenceService.class);
    private final NotificationSenderRegistry registry = mock(NotificationSenderRegistry.class);
    private final NotificationRepository repository = mock(NotificationRepository.class);
    private final NotificationSender wsSender = mock(NotificationSender.class);
    private final NotificationSender emailSender = mock(NotificationSender.class);

    private NotificationService service(Clock clock) {
        return service(clock, Severity.CRITICAL);
    }

    private NotificationService service(Clock clock, Severity minEmailSeverity) {
        return new NotificationService(cooldown, ruleCooldown, preferences, registry,
                repository, clock, minEmailSeverity, new SimpleMeterRegistry());
    }

    private ViolationEvent speedViolation() {
        return violation(Severity.HIGH);
    }

    private ViolationEvent violation(Severity severity) {
        return ViolationEvent.builder()
                .tenantId(1L).vehicleId(30L).driverId(30L)
                .ruleCode("SPEED_LIMIT").ruleType(RuleType.SPEED_LIMIT).severity(severity)
                .occurredAt(Instant.parse("2026-07-13T10:00:00Z")).value(100.0).threshold(80.0)
                .lat(41.0).lon(29.0).build();
    }

    private Clock at(String hhmm) {
        return Clock.fixed(Instant.parse("2026-07-13T" + hhmm + ":00Z"), ZoneOffset.UTC);
    }

    @Test
    void cooldownBlocksNotification() {
        when(cooldown.tryAcquire(anyLong(), anyLong(), any(), anyInt())).thenReturn(false);
        when(ruleCooldown.cooldownSeconds(anyLong(), any())).thenReturn(300);

        service(at("12:00")).onViolation(speedViolation());

        verify(preferences, never()).preferencesFor(any(), any());
        verify(repository, never()).insertNotification(any(), any());
    }

    @Test
    void dispatchesToWebSocketAndRecordsSuccess() {
        when(ruleCooldown.cooldownSeconds(anyLong(), any())).thenReturn(300);
        when(cooldown.tryAcquire(anyLong(), anyLong(), any(), anyInt())).thenReturn(true);
        when(preferences.preferencesFor(1L, "SPEED_LIMIT")).thenReturn(List.of(
                new Preference(9L, NotificationChannel.WEBSOCKET, null, null)));
        when(registry.forChannel(NotificationChannel.WEBSOCKET)).thenReturn(wsSender);
        when(wsSender.send(any())).thenReturn(true);
        when(repository.insertNotification(any(), eq("SENT"))).thenReturn(1L);

        service(at("12:00")).onViolation(speedViolation());

        verify(wsSender).send(any());
        verify(repository).insertNotification(any(), eq("SENT"));
        verify(repository).insertAttempt(eq(1L), eq("WEBSOCKET"), eq(true), any());
    }

    @Test
    void quietHoursSuppressWithoutSending() {
        when(ruleCooldown.cooldownSeconds(anyLong(), any())).thenReturn(300);
        when(cooldown.tryAcquire(anyLong(), anyLong(), any(), anyInt())).thenReturn(true);
        when(preferences.preferencesFor(1L, "SPEED_LIMIT")).thenReturn(List.of(
                new Preference(9L, NotificationChannel.WEBSOCKET,
                        LocalTime.of(22, 0), LocalTime.of(7, 0))));

        service(at("23:30")).onViolation(speedViolation());

        verify(repository).insertNotification(any(), eq("SUPPRESSED"));
        verify(registry, never()).forChannel(any());
    }

    @Test
    void emailSkippedWhenSeverityBelowThreshold() {
        when(ruleCooldown.cooldownSeconds(anyLong(), any())).thenReturn(300);
        when(cooldown.tryAcquire(anyLong(), anyLong(), any(), anyInt())).thenReturn(true);
        when(preferences.preferencesFor(1L, "SPEED_LIMIT")).thenReturn(List.of(
                new Preference(9L, NotificationChannel.EMAIL, null, null)));

        // HIGH violation, threshold CRITICAL -> EMAIL is silently skipped (no record, no sender lookup).
        service(at("12:00")).onViolation(violation(Severity.HIGH));

        verify(registry, never()).forChannel(NotificationChannel.EMAIL);
        verify(repository, never()).insertNotification(any(), any());
    }

    @Test
    void emailSentWhenSeverityMeetsThreshold() {
        when(ruleCooldown.cooldownSeconds(anyLong(), any())).thenReturn(300);
        when(cooldown.tryAcquire(anyLong(), anyLong(), any(), anyInt())).thenReturn(true);
        when(preferences.preferencesFor(1L, "SPEED_LIMIT")).thenReturn(List.of(
                new Preference(9L, NotificationChannel.EMAIL, null, null)));
        when(registry.forChannel(NotificationChannel.EMAIL)).thenReturn(emailSender);
        when(emailSender.send(any())).thenReturn(true);
        when(repository.insertNotification(any(), eq("SENT"))).thenReturn(7L);

        service(at("12:00")).onViolation(violation(Severity.CRITICAL));

        verify(emailSender).send(any());
        verify(repository).insertNotification(any(), eq("SENT"));
        verify(repository).insertAttempt(eq(7L), eq("EMAIL"), eq(true), any());
    }

    @Test
    void webSocketDeliveredRegardlessOfEmailThreshold() {
        when(ruleCooldown.cooldownSeconds(anyLong(), any())).thenReturn(300);
        when(cooldown.tryAcquire(anyLong(), anyLong(), any(), anyInt())).thenReturn(true);
        when(preferences.preferencesFor(1L, "SPEED_LIMIT")).thenReturn(List.of(
                new Preference(9L, NotificationChannel.WEBSOCKET, null, null)));
        when(registry.forChannel(NotificationChannel.WEBSOCKET)).thenReturn(wsSender);
        when(wsSender.send(any())).thenReturn(true);
        when(repository.insertNotification(any(), eq("SENT"))).thenReturn(1L);

        // LOW violation still reaches WEBSOCKET; the email threshold gates only EMAIL.
        service(at("12:00")).onViolation(violation(Severity.LOW));

        verify(wsSender).send(any());
    }
}
