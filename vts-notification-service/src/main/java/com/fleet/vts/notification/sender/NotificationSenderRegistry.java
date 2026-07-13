package com.fleet.vts.notification.sender;

import com.fleet.vts.common.enums.NotificationChannel;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Indexes the injected {@link NotificationSender} strategies by channel. */
@Component
public class NotificationSenderRegistry {

    private final Map<NotificationChannel, NotificationSender> byChannel =
            new EnumMap<>(NotificationChannel.class);

    public NotificationSenderRegistry(List<NotificationSender> senders) {
        for (NotificationSender sender : senders) {
            byChannel.put(sender.channel(), sender);
        }
    }

    public NotificationSender forChannel(NotificationChannel channel) {
        return byChannel.get(channel);
    }
}
