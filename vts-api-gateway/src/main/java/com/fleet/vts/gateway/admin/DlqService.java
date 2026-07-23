package com.fleet.vts.gateway.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * Inspecting and draining the dead-letter topics.
 *
 * <p>A dead letter is a message the pipeline could not process; without a way to see or move
 * them, they are an invisible hole where data quietly ends up. This gives an operator both —
 * how deep each DLQ is, and a way to feed the messages back in once the cause is fixed.
 *
 * <p>Replay is deliberately two different operations, because the two DLQ shapes are different
 * (see {@link DlqTopics}). Framework DLQs are republished verbatim to their source. The
 * telemetry DLQ carries {@code DlqRecord} envelopes whose inner payload is a pre-resolution
 * request, so it is re-submitted to ingestion's HTTP endpoint, not republished — sending the
 * envelope back onto the raw topic would only dead-letter it again.
 */
@Service
public class DlqService {

    private static final Logger log = LoggerFactory.getLogger(DlqService.class);
    private static final int MAX_REPLAY = 5_000;
    private static final String INGEST_BATCH_PATH = "/api/v1/telemetry/batch";

    private final String bootstrapServers;
    private final ObjectMapper objectMapper;
    private final RestClient ingestion;

    public DlqService(KafkaProperties kafkaProperties, ObjectMapper objectMapper,
                      RestClient ingestionRestClient) {
        this.bootstrapServers = String.join(",", kafkaProperties.getBootstrapServers());
        this.objectMapper = objectMapper;
        this.ingestion = ingestionRestClient;
    }

    /** How many messages sit in each DLQ right now — end offset minus beginning offset. */
    public Map<String, Long> depths() {
        Map<String, Long> depths = new LinkedHashMap<>();
        try (KafkaConsumer<byte[], byte[]> consumer = consumer()) {
            for (String topic : DlqTopics.ALL) {
                depths.put(topic, depthOf(consumer, topic));
            }
        }
        return depths;
    }

    /**
     * Replay up to {@code max} messages from {@code dlqTopic}.
     *
     * @return a small summary: how many were read and how many were re-delivered
     */
    public Map<String, Object> replay(String dlqTopic, int max) {
        if (!DlqTopics.isKnown(dlqTopic)) {
            throw new IllegalArgumentException("unknown DLQ topic: " + dlqTopic);
        }
        int limit = Math.clamp(max, 1, MAX_REPLAY);
        return DlqTopics.isTelemetryEnvelope(dlqTopic)
                ? reingestTelemetry(dlqTopic, limit)
                : republishVerbatim(dlqTopic, limit);
    }

    /** Framework DLQ: send each record's bytes back to the source topic unchanged. */
    private Map<String, Object> republishVerbatim(String dlqTopic, int limit) {
        String source = DlqTopics.sourceTopic(dlqTopic);
        int read = 0;
        int replayed = 0;
        try (KafkaConsumer<byte[], byte[]> consumer = consumer();
             KafkaProducer<byte[], byte[]> producer = producer()) {
            consumer.subscribe(List.of(dlqTopic));
            for (ConsumerRecord<byte[], byte[]> record : drain(consumer, limit)) {
                read++;
                // Original partition preserved: the DLQ kept per-vehicle ordering, and replay
                // should not scramble it.
                producer.send(new ProducerRecord<>(source, record.key(), record.value()));
                replayed++;
            }
            producer.flush();
            consumer.commitSync();
        }
        log.info("Replayed {}/{} record(s) from {} to {}", replayed, read, dlqTopic, source);
        return Map.of("dlqTopic", dlqTopic, "target", source, "read", read, "replayed", replayed);
    }

    /** Telemetry DLQ: unwrap each envelope and re-POST the inner payloads to ingestion. */
    private Map<String, Object> reingestTelemetry(String dlqTopic, int limit) {
        List<JsonNode> payloads = new ArrayList<>();
        int read = 0;
        int skipped = 0;
        try (KafkaConsumer<byte[], byte[]> consumer = consumer()) {
            consumer.subscribe(List.of(dlqTopic));
            for (ConsumerRecord<byte[], byte[]> record : drain(consumer, limit)) {
                read++;
                JsonNode payload = unwrapPayload(record.value());
                if (payload == null) {
                    skipped++;   // an envelope we could not read; left for inspection, not lost
                } else {
                    payloads.add(payload);
                }
            }
            if (!payloads.isEmpty()) {
                ingestion.post().uri(INGEST_BATCH_PATH).body(payloads).retrieve().toBodilessEntity();
            }
            consumer.commitSync();
        }
        log.info("Re-ingested {} telemetry payload(s) from {} ({} unreadable)",
                payloads.size(), dlqTopic, skipped);
        return Map.of("dlqTopic", dlqTopic, "target", "ingestion" + INGEST_BATCH_PATH,
                "read", read, "replayed", payloads.size(), "skipped", skipped);
    }

    /** The {@code payload} field of a {@code DlqRecord} envelope, or null if it does not parse. */
    JsonNode unwrapPayload(byte[] value) {
        try {
            JsonNode node = objectMapper.readTree(value);
            JsonNode payload = node.get("payload");
            return payload == null || payload.isNull() ? null : payload;
        } catch (Exception e) {
            return null;
        }
    }

    private long depthOf(KafkaConsumer<byte[], byte[]> consumer, String topic) {
        List<TopicPartition> partitions = new ArrayList<>();
        List<PartitionInfo> infos = consumer.partitionsFor(topic);
        if (infos == null) {
            return 0;
        }
        infos.forEach(p -> partitions.add(new TopicPartition(topic, p.partition())));
        long total = 0;
        Map<TopicPartition, Long> begin = consumer.beginningOffsets(partitions);
        Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
        for (TopicPartition tp : partitions) {
            total += end.getOrDefault(tp, 0L) - begin.getOrDefault(tp, 0L);
        }
        return total;
    }

    private List<ConsumerRecord<byte[], byte[]>> drain(KafkaConsumer<byte[], byte[]> consumer, int limit) {
        List<ConsumerRecord<byte[], byte[]>> out = new ArrayList<>();
        // A couple of empty polls in a row means the topic is drained, not just slow.
        int emptyPolls = 0;
        while (out.size() < limit && emptyPolls < 2) {
            ConsumerRecords<byte[], byte[]> records = consumer.poll(Duration.ofSeconds(2));
            if (records.isEmpty()) {
                emptyPolls++;
                continue;
            }
            emptyPolls = 0;
            for (ConsumerRecord<byte[], byte[]> record : records) {
                out.add(record);
                if (out.size() >= limit) {
                    break;
                }
            }
        }
        return out;
    }

    private KafkaConsumer<byte[], byte[]> consumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        // A fresh group each call, reading from the beginning: replay is a one-off admin action,
        // not a standing consumer, and it must always see everything currently on the topic.
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "vts-dlq-replay-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        return new KafkaConsumer<>(props);
    }

    private KafkaProducer<byte[], byte[]> producer() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        return new KafkaProducer<>(props);
    }
}
