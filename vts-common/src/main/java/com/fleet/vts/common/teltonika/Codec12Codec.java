package com.fleet.vts.common.teltonika;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Teltonika Codec 12 — GPRS commands, the direction the platform could not speak until now.
 *
 * <p>Codec 8 carries the fleet's telemetry up. Codec 12 carries an instruction back down the
 * same socket: ask a device where it is right now, read its firmware, open or close the
 * digital output a relay is wired to. It is what separates a system that watches a fleet from
 * one that operates it.
 *
 * <p>The outer frame is identical to Codec 8 — zero preamble, length, data field, CRC — which
 * is why the TCP framing needs no change at all. Only the data field differs:
 * <pre>
 *   codec id     1 byte, 0x0C
 *   quantity     1 byte, always 1 here (a packet may carry more; devices send one)
 *   type         1 byte, 0x05 command (to device) / 0x06 response (from device)
 *   size         4 bytes, payload length
 *   payload      ASCII, e.g. "getgps"
 *   quantity     1 byte, repeated
 * </pre>
 *
 * <p>Payloads are plain ASCII in both directions, so the same two methods serve both sides:
 * the server encodes a command and decodes a response, the device emulator does the reverse.
 */
public final class Codec12Codec {

    public static final int CODEC_12 = 0x0C;

    private static final byte TYPE_COMMAND = 0x05;
    private static final byte TYPE_RESPONSE = 0x06;

    private static final int HEADER_BYTES = 8;
    private static final int CRC_BYTES = 4;

    /** A device's answer is a status line, not a file; anything larger is a malformed packet. */
    private static final int MAX_PAYLOAD_BYTES = 8 * 1024;

    private Codec12Codec() {
    }

    /**
     * The codec id of an already-framed packet, so a session can tell telemetry from a command
     * response before trying to parse either.
     */
    public static int codecIdOf(byte[] packet) {
        if (packet.length <= HEADER_BYTES) {
            throw new TeltonikaProtocolException("Packet too short to carry a codec id");
        }
        return packet[HEADER_BYTES] & 0xFF;
    }

    /** Server to device. */
    public static byte[] encodeCommand(String command) {
        return encode(command, TYPE_COMMAND);
    }

    /** Device to server. */
    public static byte[] encodeResponse(String response) {
        return encode(response, TYPE_RESPONSE);
    }

    public static String decodeCommand(byte[] packet) {
        return decode(packet, TYPE_COMMAND);
    }

    public static String decodeResponse(byte[] packet) {
        return decode(packet, TYPE_RESPONSE);
    }

    private static byte[] encode(String payload, byte type) {
        byte[] ascii = payload.getBytes(StandardCharsets.US_ASCII);
        if (ascii.length == 0 || ascii.length > MAX_PAYLOAD_BYTES) {
            throw new TeltonikaProtocolException("Payload must be 1.." + MAX_PAYLOAD_BYTES + " bytes");
        }

        ByteBuffer data = ByteBuffer.allocate(3 + 4 + ascii.length + 1);
        data.put((byte) CODEC_12);
        data.put((byte) 1);          // quantity
        data.put(type);
        data.putInt(ascii.length);
        data.put(ascii);
        data.put((byte) 1);          // quantity, repeated

        byte[] dataField = data.array();
        ByteBuffer packet = ByteBuffer.allocate(HEADER_BYTES + dataField.length + CRC_BYTES);
        packet.putInt(0);
        packet.putInt(dataField.length);
        packet.put(dataField);
        packet.putInt(TeltonikaCrc16.compute(dataField, 0, dataField.length));
        return packet.array();
    }

    private static String decode(byte[] packet, byte expectedType) {
        if (packet.length < HEADER_BYTES + CRC_BYTES) {
            throw new TeltonikaProtocolException("Packet too short: " + packet.length + " bytes");
        }
        ByteBuffer buf = ByteBuffer.wrap(packet);
        if (buf.getInt() != 0) {
            throw new TeltonikaProtocolException("Missing four-byte zero preamble");
        }
        int dataLength = buf.getInt();
        if (dataLength <= 0 || HEADER_BYTES + dataLength + CRC_BYTES != packet.length) {
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
        if (codecId != CODEC_12) {
            throw new TeltonikaProtocolException(String.format("Not a Codec 12 packet (0x%02X)", codecId));
        }
        buf.get();   // quantity

        byte type = buf.get();
        if (type != expectedType) {
            throw new TeltonikaProtocolException(String.format(
                    "Expected type 0x%02X, got 0x%02X", expectedType, type));
        }
        int size = buf.getInt();
        if (size <= 0 || size > buf.remaining() - 1) {
            throw new TeltonikaProtocolException("Payload size " + size + " does not fit the packet");
        }
        byte[] payload = new byte[size];
        buf.get(payload);
        return new String(payload, StandardCharsets.US_ASCII);
    }
}
