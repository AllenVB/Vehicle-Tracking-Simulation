package com.fleet.vts.notification.sender;

import com.fleet.vts.common.enums.NotificationChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Mocked SMS delivery (logs). Replace with a real provider in phase 2. */
@Component
public class SmsNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(SmsNotificationSender.class);

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.SMS;
    }

    @Override
    public boolean send(NotificationMessage m) {
        log.info("[SMS mock] user={} vehicle={} rule={} :: {}",
                m.userId(), m.vehicleId(), m.ruleCode(), m.title());
        return true;
    }
}
