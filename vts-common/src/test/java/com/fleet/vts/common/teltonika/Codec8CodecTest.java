package com.fleet.vts.common.teltonika;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The decoder is checked against Teltonika's own documented example packet, not only against
 * this codebase's encoder. A round-trip test alone proves the two halves agree — including
 * when they agree on the wrong thing.
 */
class Codec8CodecTest {

    /** The Codec 8 example from Teltonika's protocol documentation: one record, CRC 0xC7CF. */
    private static final String DOC_EXAMPLE_CODEC8 =
            "000000000000003608010000016B40D8EA30010000000000000000000000000000000105021503010101"
                    + "425E0F01F10000601A014E0000000000000000010000C7CF";

    /**
     * The same record re-expressed in Codec 8 Extended, byte by byte from the specification:
     * two-byte event id, two-byte section counters, two-byte IO ids, and the extra
     * variable-length section (here empty). Hand-written rather than produced by
     * {@link Codec8Codec#encode}, so it tests the layout and not just self-consistency.
     */
    private static final String DOC_EXAMPLE_CODEC8E =
            "00000000000000438E010000016B412CEE00010000000000000000000000000000000001000500020015"
                    + "03000101000100425E0F000100F10000601A0001004E00000000000000000000010000592C";

    @Test
    void decodesTheDocumentedCodec8Example() {
        Codec8Codec.AvlPacket packet = Codec8Codec.decode(HexFormat.of().parseHex(DOC_EXAMPLE_CODEC8));

        assertThat(packet.codecId()).isEqualTo(Codec8Codec.CODEC_8);
        assertThat(packet.records()).hasSize(1);

        AvlRecord r = packet.records().get(0);
        assertThat(r.ts()).isEqualTo(Instant.ofEpochMilli(0x0000016B40D8EA30L));
        assertThat(r.priority()).isOne();
        assertThat(r.lat()).isZero();
        assertThat(r.lon()).isZero();
        assertThat(r.satellites()).isZero();
        assertThat(r.eventIoId()).isOne();

        // Two 1-byte, one 2-byte, one 4-byte and one 8-byte IO value.
        assertThat(r.io()).containsExactlyInAnyOrderEntriesOf(Map.of(
                21, 3L,
                1, 1L,
                66, 0x5E0FL,
                241, 0x0000601AL,
                78, 0L));
        assertThat(r.hasFix()).isFalse();
    }

    @Test
    void decodesTheDocumentedCodec8ExtendedExample() {
        Codec8Codec.AvlPacket packet = Codec8Codec.decode(HexFormat.of().parseHex(DOC_EXAMPLE_CODEC8E));

        assertThat(packet.codecId()).isEqualTo(Codec8Codec.CODEC_8_EXTENDED);
        assertThat(packet.records()).hasSize(1);
        assertThat(packet.records().get(0).io()).containsEntry(1, 1L);
    }

    @Test
    void roundTripsARealisticRecord() {
        AvlRecord original = new AvlRecord(
                Instant.ofEpochMilli(1_753_000_000_000L), 1,
                39.925533, 32.866287, 850, 271, 11, 87, 0,
                Map.of(
                        TeltonikaIo.IGNITION, 1L,
                        TeltonikaIo.MOVEMENT, 1L,
                        TeltonikaIo.BATTERY_LEVEL_PCT, 93L,
                        TeltonikaIo.FUEL_LEVEL_PCT, 64L,
                        TeltonikaIo.EXTERNAL_VOLTAGE_MV, 12_400L,
                        TeltonikaIo.TOTAL_ODOMETER_M, 418_237_000L));

        byte[] wire = Codec8Codec.encode(List.of(original), Codec8Codec.CODEC_8);
        AvlRecord decoded = Codec8Codec.decode(wire).records().get(0);

        assertThat(decoded.ts()).isEqualTo(original.ts());
        // Coordinates survive as degrees x 10^7, so 1e-7 is the honest tolerance.
        assertThat(decoded.lat()).isCloseTo(original.lat(), org.assertj.core.data.Offset.offset(1e-7));
        assertThat(decoded.lon()).isCloseTo(original.lon(), org.assertj.core.data.Offset.offset(1e-7));
        assertThat(decoded.speedKmh()).isEqualTo(87);
        assertThat(decoded.headingDegrees()).isEqualTo(271);
        assertThat(decoded.satellites()).isEqualTo(11);
        assertThat(decoded.io()).containsAllEntriesOf(original.io());
        assertThat(decoded.hasFix()).isTrue();
    }

    @Test
    void carriesABurstOfRecordsInOnePacket() {
        // What store-and-forward looks like on the wire: a device back from a coverage gap
        // empties its buffer into a single packet.
        List<AvlRecord> burst = java.util.stream.IntStream.range(0, 120)
                .mapToObj(i -> new AvlRecord(
                        Instant.ofEpochMilli(1_753_000_000_000L + i * 1000L), 0,
                        39.9 + i * 1e-4, 32.8 + i * 1e-4, 800, i % 360, 9, 60 + (i % 20), 0,
                        Map.of(TeltonikaIo.IGNITION, 1L)))
                .toList();

        byte[] wire = Codec8Codec.encode(burst, Codec8Codec.CODEC_8_EXTENDED);
        Codec8Codec.AvlPacket decoded = Codec8Codec.decode(wire);

        assertThat(decoded.records()).hasSize(120);
        assertThat(decoded.records().get(119).ts())
                .isEqualTo(Instant.ofEpochMilli(1_753_000_000_000L + 119_000L));
    }

    @Test
    void rejectsACorruptedPacket() {
        byte[] wire = HexFormat.of().parseHex(DOC_EXAMPLE_CODEC8);
        wire[20] ^= 0x01;   // flip a bit inside the record, leaving the CRC as it was

        assertThatThrownBy(() -> Codec8Codec.decode(wire))
                .isInstanceOf(TeltonikaProtocolException.class)
                .hasMessageContaining("CRC mismatch");
    }

    @Test
    void rejectsAWrongLengthHeader() {
        byte[] wire = HexFormat.of().parseHex(DOC_EXAMPLE_CODEC8);
        wire[7] = 0x37;   // declared data length one byte too long

        assertThatThrownBy(() -> Codec8Codec.decode(wire))
                .isInstanceOf(TeltonikaProtocolException.class)
                .hasMessageContaining("does not match packet size");
    }
}
