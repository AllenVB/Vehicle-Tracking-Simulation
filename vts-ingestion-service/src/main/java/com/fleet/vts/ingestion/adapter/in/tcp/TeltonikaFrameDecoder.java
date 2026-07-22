package com.fleet.vts.ingestion.adapter.in.tcp;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.CorruptedFrameException;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Turns the byte stream into frames. TCP is a stream, not a sequence of messages: a device's
 * packet may arrive in three reads, and two packets may arrive in one. Everything downstream
 * assumes whole packets, so this is where that assumption is made true.
 *
 * <p>A session has two phases and they are framed differently, which is why this is a
 * hand-written state machine rather than a {@code LengthFieldBasedFrameDecoder}:
 * <ul>
 *   <li>handshake — two-byte length, then that many ASCII bytes of IMEI;</li>
 *   <li>data — four zero bytes, four length bytes, the data field, then four CRC bytes.</li>
 * </ul>
 */
class TeltonikaFrameDecoder extends ByteToMessageDecoder {

    private static final int IMEI_MIN_LENGTH = 8;
    private static final int IMEI_MAX_LENGTH = 32;
    private static final int PREAMBLE_AND_LENGTH_BYTES = 8;
    private static final int CRC_BYTES = 4;

    private final int maxPacketBytes;
    private boolean handshakeDone;

    TeltonikaFrameDecoder(int maxPacketBytes) {
        this.maxPacketBytes = maxPacketBytes;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (!handshakeDone) {
            decodeImei(in, out);
        } else {
            decodeAvl(in, out);
        }
    }

    private void decodeImei(ByteBuf in, List<Object> out) {
        if (in.readableBytes() < Short.BYTES) {
            return;
        }
        in.markReaderIndex();
        int length = in.readUnsignedShort();
        if (length < IMEI_MIN_LENGTH || length > IMEI_MAX_LENGTH) {
            // Almost always a non-Teltonika client (a port scanner, a stray HTTP request).
            // Failing here beats waiting for bytes that will never come.
            throw new CorruptedFrameException("Implausible IMEI length " + length);
        }
        if (in.readableBytes() < length) {
            in.resetReaderIndex();
            return;
        }
        byte[] imei = new byte[length];
        in.readBytes(imei);
        handshakeDone = true;
        out.add(new TeltonikaFrames.Imei(new String(imei, StandardCharsets.US_ASCII)));
    }

    private void decodeAvl(ByteBuf in, List<Object> out) {
        if (in.readableBytes() < PREAMBLE_AND_LENGTH_BYTES) {
            return;
        }
        in.markReaderIndex();
        long preamble = in.readUnsignedInt();
        long dataLength = in.readUnsignedInt();
        if (preamble != 0) {
            throw new CorruptedFrameException("Expected a four-byte zero preamble, got " + preamble);
        }
        long total = PREAMBLE_AND_LENGTH_BYTES + dataLength + CRC_BYTES;
        if (dataLength <= 0 || total > maxPacketBytes) {
            throw new CorruptedFrameException("Implausible data length " + dataLength);
        }
        if (in.readableBytes() < dataLength + CRC_BYTES) {
            in.resetReaderIndex();
            return;
        }
        in.resetReaderIndex();
        byte[] packet = new byte[(int) total];
        in.readBytes(packet);
        out.add(new TeltonikaFrames.Avl(packet));
    }
}
