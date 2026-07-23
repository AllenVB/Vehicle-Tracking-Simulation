package com.fleet.vts.analytics.state;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-vehicle state for the compound "aggressive driving" rule, held in a Kafka Streams store.
 *
 * <p>Keeps the timestamps of the recent escalation-worthy violations (harsh braking, sustained
 * speeding) so the rule can answer "how many in the last N minutes" on a rolling basis — not a
 * fixed calendar bucket, which would miss two acts that straddle a bucket boundary. {@code
 * lastAlertTs} is the cooldown anchor: once an escalation fires, the same vehicle stays quiet for
 * a while rather than re-firing on its every following violation.
 */
@Getter
@Setter
@NoArgsConstructor
public class AggressionState {

    /** Event-time millis of the recent qualifying violations, pruned to the rolling window. */
    private List<Long> times = new ArrayList<>();
    private Long tenantId;
    private Double lat;
    private Double lon;
    private long lastAlertTs;
}
