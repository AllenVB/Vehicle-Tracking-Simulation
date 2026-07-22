package com.fleet.vts.common.teltonika;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Teltonika Codec 8 and Codec 8 Extended, both directions.
 *
 * <p>It lives in vts-common because two modules need the same wire format from opposite
 * sides: ingestion decodes what a device sent, the simulator encodes what a device would
 * send. Written twice, the two would drift, and the drift would look like a device bug.
 *
 * <p>Packet layout (all big-endian):
 * <pre>
 *   00 00 00 00              4 bytes, zero preamble
 *   data field length        4 bytes, counts codec id .. trailing record count
 *   codec id                 1 byte, 0x08 or 0x8E
 *   number of records        1 byte
 *   records                  variable
 *   number of records        1 byte, repeated
 *   CRC-16                   4 bytes, over the data field only
 * </pre>
 *
 * <p>Codec 8 Extended differs only inside the IO element: ids and counters are two bytes
 * instead of one, and a variable-length section is appended. That is why one class handles
 * both — a separate decoder would be the same 200 lines with two constants changed.
 */
public final class Codec8Codec {

    public static final int CODEC_8 = 0x08;
    public static final int CODEC_8_EXTENDED = 0x8E;

    /** Bytes before the data field: 4 preamble + 4 length. */
    private static final int HEADER_BYTES = 8;
    private static final int CRC_BYTES = 4;

    /** Coordinates travel as degrees x 10^7 in a signed 32-bit integer. */
    private static final double COORD_SCALE = 1e7;

    private Codec8Codec() {
    }

    /** A decoded packet: which codec produced it, and the records it carried. */
    public record AvlPacket(int codecId, List<AvlRecord> records) {
    }

    // ── Decoding ─────────────────────────────────────────────────────────────

    /**
     * Parses a complete packet, verifying preamble, declared length and CRC.
     *
     * @throws TeltonikaProtocolException if the bytes are not a valid packet
     */
    public static AvlPacket decode(byte[] packet) {
        if (packet.length < HEADER_BYTES + CRC_BYTES) {
            throw new TeltonikaProtocolException("Packet too short: " + packet.length + " bytes");
        }
        ByteBuffer buf = ByteBuffer.wrap(packet);

        if (buf.getInt() != 0) {
            throw new TeltonikaProtocolException("Missing four-byte zero preamble");
        }
        int dataLength = buf.getInt();
        if (dataLength < 0 || HEADER_BYTES + dataLength + CRC_BYTES != packet.length) {
            throw new TeltonikaProtocolException("Declared data length " + dataLength
                    + " does not match packet size " + packet.length);
        }

        int expectedCrc = ByteBuffer.wrap(packet, HEADER_BYTES + dataLength, CRC_BYTES).getInt();
        int actualCrc = TeltonikaCrc16.compute(packet, HEADER_BYTES, dataLength);
        if (expectedCrc != actualCrc) {
            throw new TeltonikaProtocolException(String.format(
                    "CRC mismatch: packet says 0x%04X, data is 0x%04X", expectedCrc, actualCrc));
        }

        int codecId = buf.get() & 0xFF;
        if (codecId != CODEC_8 && codecId != CODEC_8_EXTENDED) {
            throw new TeltonikaProtocolException(String.format("Unsupported codec 0x%02X", codecId));
        }
        boolean extended = codecId == CODEC_8_EXTENDED;

        int count = buf.get() & 0xFF;
        List<AvlRecord> records = new ArrayList<>(count);
        try {
            for (int i = 0; i < count; i++) {
                records.add(readRecord(buf, extended));
            }
            int trailingCount = buf.get() & 0xFF;
            if (trailingCount != count) {
                throw new TeltonikaProtocolException(
                        "Record count mismatch: header " + count + ", trailer " + trailingCount);
            }
        } catch (BufferUnderflowException e) {
            throw new TeltonikaProtocolException("Packet ended mid-record");
        }
        return new AvlPacket(codecId, records);
    }

    private static AvlRecord readRecord(ByteBuffer buf, boolean extended) {
        Instant ts = Instant.ofEpochMilli(buf.getLong());
        int priority = buf.get() & 0xFF;

        double lon = buf.getInt() / COORD_SCALE;
        double lat = buf.getInt() / COORD_SCALE;
        int altitude = buf.getShort();
        int heading = buf.getShort() & 0xFFFF;
        int satellites = buf.get() & 0xFF;
        int speed = buf.getShort() & 0xFFFF;

        int eventIoId = extended ? buf.getShort() & 0xFFFF : buf.get() & 0xFF;
        readCounter(buf, extended);   // total id count: informational, the sections are authoritative

        Map<Integer, Long> io = new LinkedHashMap<>();
        readIoSection(buf, extended, io, 1);
        readIoSection(buf, extended, io, 2);
        readIoSection(buf, extended, io, 4);
        readIoSection(buf, extended, io, 8);
        if (extended) {
            int variableCount = buf.getShort() & 0xFFFF;
            for (int i = 0; i < variableCount; i++) {
                int id = buf.getShort() & 0xFFFF;
                int length = buf.getShort() & 0xFFFF;
                // Variable-length values are free-form (CAN frames, ICCID, RFID text). They are
                // skipped rather than guessed at: nothing this platform reads is carried here.
                buf.position(buf.position() + length);
                io.putIfAbsent(id, null);
            }
        }
        return new AvlRecord(ts, priority, lat, lon, altitude, heading, satellites, speed,
                eventIoId, io);
    }

    private static void readIoSection(ByteBuffer buf, boolean extended,
                                      Map<Integer, Long> io, int valueBytes) {
        int count = readCounter(buf, extended);
        for (int i = 0; i < count; i++) {
            int id = extended ? buf.getShort() & 0xFFFF : buf.get() & 0xFF;
            io.put(id, readUnsigned(buf, valueBytes));
        }
    }

    private static int readCounter(ByteBuffer buf, boolean extended) {
        return extended ? buf.getShort() & 0xFFFF : buf.get() & 0xFF;
    }

    private static long readUnsigned(ByteBuffer buf, int valueBytes) {
        return switch (valueBytes) {
            case 1 -> buf.get() & 0xFFL;
            case 2 -> buf.getShort() & 0xFFFFL;
            case 4 -> buf.getInt() & 0xFFFFFFFFL;
            // Eight-byte values are the only signed ones in practice (e.g. CAN counters).
            case 8 -> buf.getLong();
            default -> throw new TeltonikaProtocolException("Bad IO value width " + valueBytes);
        };
    }

    // ── Encoding ─────────────────────────────────────────────────────────────

    /**
     * Builds a complete packet from {@code records}, ready to write to a socket.
     *
     * <p>Used by the device emulator. Encoding here rather than in the simulator is what
     * makes the emulator worth having: it exercises this decoder's counterpart, so a
     * misunderstanding of the format cannot be symmetric and therefore invisible.
     */
    public static byte[] encode(List<AvlRecord> records, int codecId) {
        if (codecId != CODEC_8 && codecId != CODEC_8_EXTENDED) {
            throw new TeltonikaProtocolException(String.format("Unsupported codec 0x%02X", codecId));
        }
        if (records.isEmpty() || records.size() > 255) {
            throw new TeltonikaProtocolException("A packet carries 1..255 records, not " + records.size());
        }
        boolean extended = codecId == CODEC_8_EXTENDED;

        // Generous upper bound; the buffer is trimmed to the bytes actually written.
        ByteBuffer data = ByteBuffer.allocate(2 + records.size() * 1024 + 1);
        data.put((byte) codecId);
        data.put((byte) records.size());
        for (AvlRecord r : records) {
            writeRecord(data, r, extended);
        }
        data.put((byte) records.size());

        byte[] dataField = new byte[data.position()];
        data.flip();
        data.get(dataField);

        ByteBuffer packet = ByteBuffer.allocate(HEADER_BYTES + dataField.length + CRC_BYTES);
        packet.putInt(0);
        packet.putInt(dataField.length);
        packet.put(dataField);
        packet.putInt(TeltonikaCrc16.compute(dataField, 0, dataField.length));
        return packet.array();
    }

    private static void writeRecord(ByteBuffer buf, AvlRecord r, boolean extended) {
        buf.putLong(r.ts().toEpochMilli());
        buf.put((byte) r.priority());

        buf.putInt((int) Math.round(r.lon() * COORD_SCALE));
        buf.putInt((int) Math.round(r.lat() * COORD_SCALE));
        buf.putShort((short) r.altitudeMeters());
        buf.putShort((short) r.headingDegrees());
        buf.put((byte) r.satellites());
        buf.putShort((short) r.speedKmh());

        writeId(buf, r.eventIoId(), extended);

        // Group IO values by the narrowest width that holds them, which is what a device does:
        // sending a 0/1 ignition flag as eight bytes is legal and nobody's device does it.
        Map<Integer, Long> byOne = new LinkedHashMap<>();
        Map<Integer, Long> byTwo = new LinkedHashMap<>();
        Map<Integer, Long> byFour = new LinkedHashMap<>();
        Map<Integer, Long> byEight = new LinkedHashMap<>();
        r.io().forEach((id, value) -> {
            if (value == null) {
                return;
            }
            if (value >= 0 && value <= 0xFFL) {
                byOne.put(id, value);
            } else if (value >= 0 && value <= 0xFFFFL) {
                byTwo.put(id, value);
            } else if (value >= 0 && value <= 0xFFFFFFFFL) {
                byFour.put(id, value);
            } else {
                byEight.put(id, value);
            }
        });

        int total = byOne.size() + byTwo.size() + byFour.size() + byEight.size();
        writeCounter(buf, total, extended);
        writeIoSection(buf, byOne, 1, extended);
        writeIoSection(buf, byTwo, 2, extended);
        writeIoSection(buf, byFour, 4, extended);
        writeIoSection(buf, byEight, 8, extended);
        if (extended) {
            writeCounter(buf, 0, extended);   // no variable-length values
        }
    }

    private static void writeIoSection(ByteBuffer buf, Map<Integer, Long> values,
                                       int valueBytes, boolean extended) {
        writeCounter(buf, values.size(), extended);
        values.forEach((id, value) -> {
            writeId(buf, id, extended);
            switch (valueBytes) {
                case 1 -> buf.put(value.byteValue());
                case 2 -> buf.putShort(value.shortValue());
                case 4 -> buf.putInt(value.intValue());
                default -> buf.putLong(value);
            }
        });
    }

    private static void writeId(ByteBuffer buf, int id, boolean extended) {
        if (extended) {
            buf.putShort((short) id);
        } else {
            buf.put((byte) id);
        }
    }

    private static void writeCounter(ByteBuffer buf, int count, boolean extended) {
        if (extended) {
            buf.putShort((short) count);
        } else {
            buf.put((byte) count);
        }
    }
}
