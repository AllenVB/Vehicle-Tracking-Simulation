package com.fleet.vts.analytics.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Event-time tuning for the stateful rules. */
@ConfigurationProperties(prefix = "vts.analytics")
public class AnalyticsProperties {

    private EventTime eventTime = new EventTime();

    public EventTime getEventTime() {
        return eventTime;
    }

    public void setEventTime(EventTime eventTime) {
        this.eventTime = eventTime;
    }

    public static class EventTime {

        /**
         * How long a window stays open for readings that were recorded inside it but arrived
         * after it closed.
         *
         * <p>Grace is not free and there is no value that is simply correct. Wider means more
         * buffered history is counted; it also delays every windowed violation by exactly this
         * much, because a suppressed window only emits once stream time passes its end plus
         * the grace. Fifteen minutes is chosen against what the device channel actually
         * produces — the emulator's buffer holds an hour, but a gap longer than a quarter of
         * an hour is an outage, and an outage's readings belong in history rather than in a
         * speeding alert nobody can act on any more.
         *
         * <p>Readings later than this still reach the database, the trip and the dashboards.
         * What they miss is the windowed rule.
         */
        private Duration grace = Duration.ofMinutes(15);

        /**
         * Extra wait before the punctuator closes a trip that has gone quiet.
         *
         * <p>Stream time is shared across a partition, so it keeps advancing on other
         * vehicles' readings while one device is out of coverage. Without this, that silence
         * looks exactly like a stop: the trip closes after 90 seconds, and when the device
         * reconnects its buffered readings open a second trip. One journey becomes two, both
         * scored, and the distance splits between them.
         *
         * <p>So the punctuator waits {@code stop window + this} before acting. A device back
         * within the window updates the trip and nothing closes; one that stays away longer
         * does get split, and that is the honest limit of what a stateless-in-time system can
         * do without holding every open trip forever.
         */
        private Duration tripCloseGrace = Duration.ofMinutes(15);

        public Duration getGrace() {
            return grace;
        }

        public void setGrace(Duration grace) {
            this.grace = grace;
        }

        public Duration getTripCloseGrace() {
            return tripCloseGrace;
        }

        public void setTripCloseGrace(Duration tripCloseGrace) {
            this.tripCloseGrace = tripCloseGrace;
        }
    }
}
