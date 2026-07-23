package com.fleet.vts.gateway.admin;

import com.fleet.vts.common.topic.Topics;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The DLQ→source mapping, which is the only part of replay that can be quietly wrong: send a
 * dead letter to the wrong topic and it either vanishes or loops. Pure logic, so it is tested
 * without a broker.
 */
class DlqTopicsTest {

    @Test
    void frameworkDlqMapsToItsSourceTopic() {
        assertThat(DlqTopics.sourceTopic(Topics.VIOLATION + ".dlq")).isEqualTo(Topics.VIOLATION);
        assertThat(DlqTopics.sourceTopic(Topics.TRIP + ".dlq")).isEqualTo(Topics.TRIP);
        assertThat(DlqTopics.sourceTopic(Topics.NOTIFICATION + ".dlq")).isEqualTo(Topics.NOTIFICATION);
    }

    @Test
    void telemetryDlqIsAnEnvelopeAndHasNoVerbatimSource() {
        assertThat(DlqTopics.isTelemetryEnvelope(Topics.TELEMETRY_DLQ)).isTrue();
        // Asking for its verbatim source is a bug — it re-ingests instead.
        assertThatThrownBy(() -> DlqTopics.sourceTopic(Topics.TELEMETRY_DLQ))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownTopicsAreRejected() {
        assertThat(DlqTopics.isKnown("vehicle.made.up.dlq")).isFalse();
        assertThat(DlqTopics.isKnown(Topics.VIOLATION + ".dlq")).isTrue();
        assertThatThrownBy(() -> DlqTopics.sourceTopic("not-a-dlq"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
