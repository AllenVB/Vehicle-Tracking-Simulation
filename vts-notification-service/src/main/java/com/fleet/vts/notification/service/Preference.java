package com.fleet.vts.notification.service;

import com.fleet.vts.common.enums.NotificationChannel;

import java.time.LocalTime;

/** A user's enabled notification preference for a channel, with quiet hours. */
public record Preference(
        Long userId,
        NotificationChannel channel,
        LocalTime quietStart,
        LocalTime quietEnd) {

    /** True if {@code now} falls within quiet hours (handles overnight ranges). */
    public boolean isQuiet(LocalTime now) {
        if (quietStart == null || quietEnd == null) {
            return false;
        }
        if (quietStart.equals(quietEnd)) {
            return false;
        }
        if (quietStart.isBefore(quietEnd)) {
            return !now.isBefore(quietStart) && now.isBefore(quietEnd);
        }
        // overnight window, e.g. 22:00 - 07:00
        return !now.isBefore(quietStart) || now.isBefore(quietEnd);
    }
}
