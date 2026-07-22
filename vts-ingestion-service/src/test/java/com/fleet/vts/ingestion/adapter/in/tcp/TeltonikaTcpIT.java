package com.fleet.vts.ingestion.adapter.in.tcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleet.vts.common.teltonika.AvlRecord;
import com.fleet.vts.common.teltonika.Codec8Codec;
import com.fleet.vts.common.teltonika.TeltonikaIo;
import com.fleet.vts.common.topic.Topics;
import com.fleet.vts.testsupport.VtsContainers;
import com.fleet.vts.testsupport.VtsInfra;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.DataInputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The device channel, end to end: a socket speaks Codec 8 Extended and telemetry comes out
 * on the Kafka topic.
 *
 * <p>The interesting assertion is not that records arrive — it is that their timestamps
 * survive. The records sent here are two hours old and out of order, which is what a device
 * back from a coverage gap produces and what the HTTP path could never have produced.
 */
@SpringBootTest(properties = {
        // Port 0: the OS picks a free one, so the test never collides with a running stack.
        "vts.ingestion.teltonika.port=0",
        "management.tracing.sampling.probability=0.0"
})
class TeltonikaTcpIT {

    /** Seeded by V15: devices carry the zero-padded vehicle number as IMEI. */
    private static final String KNOWN_IMEI = "000000000000001";

    private static final ObjectMapper JSON = new ObjectMapper();

    @DynamicPropertySource
    static void infra(DynamicPropertyRegistry registry) {
        VtsInfra.all(registry);
    }

    /**
     * Created up front rather than left to auto-creation: a consumer that subscribes to a
     * topic which does not exist yet can start reading after the records under test were
     * already produced, and the test would fail for a reason that is not the code's.
     */
    @BeforeAll
    static void createRawTopic() throws Exception {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                VtsContainers.kafkaBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(Topics.TELEMETRY_RAW, 1, (short) 1))).all().get();
        } catch (Exception e) {
            if (!(e.getCause() instanceof TopicExistsException)) {
                throw e;
            }
        }
    }

    @Autowired
    private TeltonikaTcpServer server;

    @Test
    void deliversBufferedOutOfOrderRecordsWithTheirOwnTimestamps() throws Exception {
        Instant now = Instant.now();
        // Deliberately shuffled and deliberately old: a flash buffer is not a queue with
        // guarantees, and 07:xx can follow 09:xx on the wire.
        List<AvlRecord> burst = List.of(
                reading(now.minus(Duration.ofHours(2)), 39.90, 32.80, 61),
                reading(now.minus(Duration.ofMinutes(30)), 39.95, 32.85, 74),
                reading(now.minus(Duration.ofHours(1)), 39.92, 32.83, 68));

        try (KafkaConsumer<String, String> consumer = rawTopicConsumer();
             Socket socket = new Socket("localhost", server.boundPort())) {

            OutputStream out = socket.getOutputStream();
            DataInputStream in = new DataInputStream(socket.getInputStream());

            assertThat(handshake(out, in, KNOWN_IMEI)).as("handshake reply").isEqualTo(0x01);

            out.write(Codec8Codec.encode(burst, Codec8Codec.CODEC_8_EXTENDED));
            out.flush();

            assertThat(in.readInt())
                    .as("the server acknowledges every record, or the device resends them")
                    .isEqualTo(burst.size());

            List<JsonNode> events = drain(consumer, burst.size(), Duration.ofSeconds(30));
            assertThat(events).hasSize(3);
            assertThat(events).allSatisfy(e ->
                    assertThat(e.get("imei").asText()).isEqualTo(KNOWN_IMEI));

            List<Instant> timestamps = events.stream()
                    .map(e -> Instant.parse(e.get("ts").asText()))
                    .sorted()
                    .toList();
            // Two hours late, to the second. Anything that stamped arrival time would put all
            // three within a second of each other.
            assertThat(Duration.between(timestamps.get(0), now).toMinutes()).isEqualTo(120);
            assertThat(Duration.between(timestamps.get(2), now).toMinutes()).isEqualTo(30);

            // IO ids became platform fields.
            JsonNode any = events.get(0);
            assertThat(any.get("fuelPct").asInt()).isEqualTo(64);
            assertThat(any.get("battery").asInt()).isEqualTo(93);
            assertThat(any.get("ignition").asBoolean()).isTrue();
        }
    }

    @Test
    void refusesAnUnknownDeviceAtTheHandshake() throws Exception {
        try (Socket socket = new Socket("localhost", server.boundPort())) {
            int reply = handshake(socket.getOutputStream(),
                    new DataInputStream(socket.getInputStream()), "999999999999999");

            assertThat(reply).as("0x00 means 'not my device'").isZero();
        }
    }

    private static int handshake(OutputStream out, DataInputStream in, String imei) throws Exception {
        byte[] ascii = imei.getBytes(StandardCharsets.US_ASCII);
        out.write(new byte[]{(byte) (ascii.length >> 8), (byte) ascii.length});
        out.write(ascii);
        out.flush();
        return in.read();
    }

    private static AvlRecord reading(Instant ts, double lat, double lon, int speedKmh) {
        return new AvlRecord(ts, 1, lat, lon, 850, 180, 9, speedKmh, 0, Map.of(
                TeltonikaIo.IGNITION, 1L,
                TeltonikaIo.MOVEMENT, 1L,
                TeltonikaIo.BATTERY_LEVEL_PCT, 93L,
                TeltonikaIo.FUEL_LEVEL_PCT, 64L,
                TeltonikaIo.TOTAL_ODOMETER_M, 12_000L));
    }

    private static KafkaConsumer<String, String> rawTopicConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, VtsContainers.kafkaBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "tcp-it-" + System.nanoTime());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(List.of(Topics.TELEMETRY_RAW));
        // Force the assignment before anything is produced, so "latest" starts here and not
        // after the records under test have already gone by.
        consumer.poll(Duration.ofSeconds(5));
        return consumer;
    }

    private static List<JsonNode> drain(KafkaConsumer<String, String> consumer, int expected,
                                        Duration timeout) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        Instant deadline = Instant.now().plus(timeout);
        while (out.size() < expected && Instant.now().isBefore(deadline)) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(1));
            for (ConsumerRecord<String, String> record : records) {
                out.add(JSON.readTree(record.value()));
            }
        }
        return out;
    }
}
