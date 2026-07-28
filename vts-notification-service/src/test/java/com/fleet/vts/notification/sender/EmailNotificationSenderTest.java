package com.fleet.vts.notification.sender;

import com.fleet.vts.common.enums.NotificationChannel;
import com.fleet.vts.common.enums.Severity;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Recipient resolution, SMTP dispatch and failure handling with mocked collaborators. */
class EmailNotificationSenderTest {

    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final RecipientResolver recipients = mock(RecipientResolver.class);
    private final EmailNotificationSender sender =
            new EmailNotificationSender(mailSender, recipients, "fleet-alerts@vts.local");

    private NotificationMessage message() {
        return new NotificationMessage(1L, 9L, 30L, 42L, "AGGRESSIVE_DRIVING",
                Severity.CRITICAL, NotificationChannel.EMAIL, "AGGRESSIVE_DRIVING ihlali",
                "Araç 42 kural AGGRESSIVE_DRIVING", null, Instant.parse("2026-07-13T10:00:00Z"));
    }

    @Test
    void sendsMailWhenRecipientResolves() {
        when(recipients.emailFor(9L)).thenReturn(Optional.of("admin@demo.local"));

        boolean ok = sender.send(message());

        assertThat(ok).isTrue();
        var captor = org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage sent = captor.getValue();
        assertThat(sent.getTo()).containsExactly("admin@demo.local");
        assertThat(sent.getFrom()).isEqualTo("fleet-alerts@vts.local");
        assertThat(sent.getSubject()).isEqualTo("[VTS][CRITICAL] AGGRESSIVE_DRIVING ihlali");
        assertThat(sent.getText()).contains("Araç 42 kural AGGRESSIVE_DRIVING", "AGGRESSIVE_DRIVING");
    }

    @Test
    void skipsWhenNoRecipient() {
        when(recipients.emailFor(9L)).thenReturn(Optional.empty());

        boolean ok = sender.send(message());

        assertThat(ok).isFalse();
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void returnsFalseOnMailException() {
        when(recipients.emailFor(9L)).thenReturn(Optional.of("admin@demo.local"));
        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(SimpleMailMessage.class));

        boolean ok = sender.send(message());

        assertThat(ok).isFalse();
    }

    @Test
    void channelIsEmail() {
        assertThat(sender.channel()).isEqualTo(NotificationChannel.EMAIL);
    }
}
