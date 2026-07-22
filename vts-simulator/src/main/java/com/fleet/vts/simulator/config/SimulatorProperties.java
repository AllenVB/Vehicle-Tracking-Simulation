package com.fleet.vts.simulator.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Simulator configuration. Fleet size and tick come only from the active
 * profile (dev: 100 / 5s, load: 1000 / 1s) — never hard-coded.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "vts.simulator")
public class SimulatorProperties {

    /** Number of simulated vehicles (their IMEIs are 000000000000001..N). */
    private int vehicleCount = 100;

    /** Interval between simulation ticks. */
    private Duration tick = Duration.ofSeconds(5);

    /** Base URL of the ingestion service. */
    private String ingestionBaseUrl = "http://localhost:8081";

    /** How long to wait for a TCP connection to ingestion before failing the tick. */
    private Duration ingestionConnectTimeout = Duration.ofSeconds(2);

    /**
     * How long to wait for ingestion's response. Bounded so a stalled ingestion cannot
     * pin the simulator's tick thread indefinitely — a dropped batch is recoverable,
     * a wedged simulator is not.
     */
    private Duration ingestionReadTimeout = Duration.ofSeconds(10);

    /**
     * When true, land vehicles only ever travel on OSRM-routed roads; when a route cannot
     * be obtained they stay parked rather than move off-road. Turning this off leaves land
     * vehicles permanently parked — helicopters are unaffected, they fly point to point.
     */
    private boolean roadRouting = true;

    /**
     * OSRM routing service base URL. Defaults to a local instance (the {@code osrm} service
     * in docker-compose): this used to point at OSRM's public demo server, whose latency and
     * rate limits decided whether our fleet stayed on the road.
     */
    private String osrmBaseUrl = "http://localhost:5000";

    /**
     * How much of the tank a land vehicle burns per minute of driving. Helicopters are outside
     * the fuel model and ignore this — they cannot use a roadside pump, so draining them would
     * only strand them.
     *
     * <p>This is paired with how densely the map is stocked with stations, and the two cannot
     * be chosen apart: what matters is whether the quarter-tank left when the warning fires
     * still covers the distance to the nearest pump. With stations capped at five per province
     * (see migration V28) the farthest a vehicle was measured from one is 50 km — call it 65 km
     * of road. The slowest vehicles cruise at 35 km/h, so that is a 111-minute run, and 25% of
     * the tank has to outlast it. That puts the ceiling at 0.22%/min.
     *
     * <p>Hence 0.2: a full tank lasts ~8 hours and the warning arrives after ~6. Sized for the
     * compound worst case — the farthest-placed vehicle also being one of the slowest — because
     * a vehicle that strands is a dead marker on the map with no way back, while an unhurried
     * tank merely makes refuelling occasional. That is the real cost here: with a station map
     * sparse enough to be realistic, the refuel cycle is something you catch rather than watch.
     * Raise this to compress it for a short demo, but raise station density with it or vehicles
     * will start running dry in the east, where the corridors are longest.
     */
    private double fuelDrainPctPerMinute = 0.2;

    /**
     * Tank level at or below which a vehicle is flagged low and sent to the nearest station.
     * Kept in step with the {@code LOW_FUEL} rule threshold, so the map's warning and the
     * recorded violation mean the same thing.
     */
    private double lowFuelThresholdPct = 25.0;

    /** How long a vehicle stands at the pump before setting off again. */
    private Duration refuelDwell = Duration.ofMinutes(1);

    /**
     * How far from a road an operator's click may land and still count as "on that road",
     * for moving a land vehicle. Beyond it the move is refused.
     *
     * <p>This is the whole definition of an on-road click, so it is a setting rather than a
     * constant. Too tight and a click that visually lands on a road is rejected: the click
     * carries the map's zoom error, the road's rendered width and OSM's own geometry error.
     * Too loose and the vehicle silently lands somewhere the operator did not point at,
     * which is the behaviour this replaced.
     *
     * <p>50 m is about a city block's width: unambiguous about which road was meant, while
     * forgiving of a click near a road's edge. Note the operator still has to be zoomed in
     * enough to mean a specific road — at a regional zoom a pixel is hundreds of metres, and
     * refusing there is correct.
     */
    private double roadClickToleranceMeters = 50.0;

    /**
     * Which wire the fleet talks over: JSON/HTTP, or the binary Teltonika protocol a real
     * tracker speaks. Defaults to HTTP so nothing that depended on it changes by accident;
     * docker-compose selects TELTONIKA.
     */
    private Transport transport = Transport.HTTP;

    /** Device-emulator settings, used only when {@link #transport} is TELTONIKA. */
    private Teltonika teltonika = new Teltonika();

    public enum Transport {
        HTTP, TELTONIKA
    }

    @Getter
    @Setter
    public static class Teltonika {

        /** Ingestion's TCP listener. */
        private String host = "localhost";

        private int port = 5027;

        /** {@code 8} or {@code 8E}. Extended carries two-byte IO ids and is what newer FMBs use. */
        private String codec = "8E";

        /**
         * How many readings a device holds while it cannot transmit.
         *
         * <p>This is what makes the emulator a device rather than a client: a real tracker
         * out of coverage keeps recording to flash and empties it on reconnect. 3600 at one
         * reading a second is an hour of silence — past that the oldest are dropped, which is
         * also what the hardware does.
         */
        private int bufferSize = 3600;

        /**
         * Records per packet. The protocol allows 255; devices usually send fewer so that a
         * failed packet costs less to resend.
         */
        private int maxRecordsPerPacket = 120;

        /** Socket connect and read timeout. The ACK is the only thing ever read. */
        private Duration timeout = Duration.ofSeconds(10);
    }
}
