package com.fleet.vts.notification.sender;

import com.fleet.vts.common.enums.NotificationChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Mocked push delivery (logs). Wire to FCM in phase 2. */
@Component
public class PushNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(PushNotificationSender.class);

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.PUSH;
    }

    @Override
    public boolean send(NotificationMessage m) {
        log.info("[PUSH mock] user={} vehicle={} rule={} :: {}",
                m.userId(), m.vehicleId(), m.ruleCode(), m.title());
        return true;
    }
}
