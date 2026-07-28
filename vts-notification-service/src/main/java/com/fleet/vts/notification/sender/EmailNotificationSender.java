package com.fleet.vts.notification.sender;

import com.fleet.vts.common.enums.NotificationChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Real e-mail delivery over SMTP. In dev this targets the MailHog trap
 * ({@code mailhog:1025}); MailHog captures every message regardless of address,
 * so the mail is inspectable at its web UI. The recipient is resolved from the
 * notification's user; a user without an e-mail (or a transport failure) yields
 * {@code false} so the dispatcher records the attempt as FAILED rather than SENT.
 */
@Component
public class EmailNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationSender.class);

    private final JavaMailSender mailSender;
    private final RecipientResolver recipients;
    private final String from;

    public EmailNotificationSender(JavaMailSender mailSender, RecipientResolver recipients,
                                   @Value("${vts.notification.email.from:fleet-alerts@vts.local}") String from) {
        this.mailSender = mailSender;
        this.recipients = recipients;
        this.from = from;
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.EMAIL;
    }

    @Override
    public boolean send(NotificationMessage m) {
        Optional<String> to = recipients.emailFor(m.userId());
        if (to.isEmpty()) {
            log.warn("E-posta atlandi: user={} icin adres bulunamadi (rule={})", m.userId(), m.ruleCode());
            return false;
        }
        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(from);
        mail.setTo(to.get());
        mail.setSubject("[VTS][" + m.severity() + "] " + m.title());
        mail.setText(buildBody(m));
        try {
            mailSender.send(mail);
            log.info("E-posta gonderildi: to={} vehicle={} rule={} severity={}",
                    to.get(), m.vehicleId(), m.ruleCode(), m.severity());
            return true;
        } catch (MailException e) {
            log.warn("E-posta gonderilemedi: to={} rule={} :: {}", to.get(), m.ruleCode(), e.getMessage());
            return false;
        }
    }

    private static String buildBody(NotificationMessage m) {
        StringBuilder b = new StringBuilder();
        b.append(m.body()).append("\n\n");
        b.append("Önem: ").append(m.severity()).append('\n');
        b.append("Araç ID: ").append(m.vehicleId()).append('\n');
        if (m.driverId() != null) {
            b.append("Sürücü ID: ").append(m.driverId()).append('\n');
        }
        b.append("Kural: ").append(m.ruleCode()).append('\n');
        if (m.occurredAt() != null) {
            b.append("Zaman: ").append(m.occurredAt()).append('\n');
        }
        b.append("\n-- VTS Filo İzleme");
        return b.toString();
    }
}
