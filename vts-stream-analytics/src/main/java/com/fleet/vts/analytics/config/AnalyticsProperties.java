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

        /**
         * Hard ceiling on how long a single trip may run before it is force-closed and a fresh
         * one opened in its place.
         *
         * <p>A trip normally ends when the vehicle sits still for {@code TripRule.STOP_MILLIS};
         * that is how a journey's arrival park closes it. But some vehicles never produce that
         * still stretch — the geofence lap car circles forever, and a fuel-hopper stops only for
         * the sub-window refuel dwell — so their one trip runs for days and swallows dozens of
         * journeys. Every km-normalised driver score built on such a trip is meaningless, and its
         * entry in the history reads as a single 6000&nbsp;km, 160-hour drive.
         *
         * <p>Ten hours is chosen against the fleet's own shape: the longest legitimate single
         * journey a vehicle drives (slowest cruise, farthest of the eight nearest provinces) tops
         * out around eight hours, and measured trip durations are cleanly bimodal — a mass below
         * eight hours and a mass above sixteen, with nothing in between. Ten hours sits in that
         * empty band, so no real journey is ever split while every runaway trip is capped.
         */
        private Duration maxTripDuration = Duration.ofHours(10);

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

        public Duration getMaxTripDuration() {
            return maxTripDuration;
        }

        public void setMaxTripDuration(Duration maxTripDuration) {
            this.maxTripDuration = maxTripDuration;
        }
    }
}
