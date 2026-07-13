package com.fleet.vts.analytics.state;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Per-vehicle trip accumulator held in a Kafka Streams state store. */
@Getter
@Setter
@NoArgsConstructor
public class TripState {

    private boolean open;
    private Long tenantId;
    private Long driverId;
    private long startTs;
    private double startLat;
    private double startLon;
    private long lastMoveTs;
    private double lastLat;
    private double lastLon;
    private double distanceKm;
    private int maxSpeed;
    private double speedSum;
    private int sampleCount;
}
