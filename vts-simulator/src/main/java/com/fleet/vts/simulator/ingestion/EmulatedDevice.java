package com.fleet.vts.simulator.ingestion;

import com.fleet.vts.common.teltonika.AvlRecord;
import com.fleet.vts.common.teltonika.Codec8Codec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * One emulated Teltonika tracker: a socket, a flash buffer, and the discipline of only
 * discarding what the server has acknowledged.
 *
 * <p>The buffer is the reason this class exists. A tracker does not fail to send — it records
 * to flash and sends later, so a reading taken at 09:00 can arrive at 11:00 alongside two
 * hours of its neighbours, out of order relative to everything else the platform has seen.
 * Any component that read arrival time as event time was correct only because the old HTTP
 * simulator never let that happen.
 *
 * <p>Not thread-safe by itself; the transport gives each device one flusher at a time.
 */
class EmulatedDevice {

    private static final Logger log = LoggerFactory.getLogger(EmulatedDevice.class);

    private final String imei;
    private final String host;
    private final int port;
    private final int codecId;
    private final int bufferSize;
    private final int maxRecordsPerPacket;
    private final int timeoutMillis;

    private final Deque<AvlRecord> buffer = new ArrayDeque<>();

    private Socket socket;
    private OutputStream out;
    private DataInputStream in;

    /** While set in the future the device records but does not transmit. */
    private volatile Instant silentUntil = Instant.EPOCH;

    private long droppedRecords;

    EmulatedDevice(String imei, String host, int port, int codecId,
                   int bufferSize, int maxRecordsPerPacket, int timeoutMillis) {
        this.imei = imei;
        this.host = host;
        this.port = port;
        this.codecId = codecId;
        this.bufferSize = bufferSize;
        this.maxRecordsPerPacket = maxRecordsPerPacket;
        this.timeoutMillis = timeoutMillis;
    }

    /** Record a reading to flash. Always succeeds; the oldest is dropped when full. */
    synchronized void record(AvlRecord record) {
        if (buffer.size() >= bufferSize) {
            buffer.removeFirst();
            droppedRecords++;
        }
        buffer.addLast(record);
    }

    /** Stop transmitting for {@code seconds} while continuing to record. */
    void goSilent(long seconds) {
        this.silentUntil = Instant.now().plusSeconds(seconds);
        log.info("Device {} going silent for {} s (records keep buffering)", imei, seconds);
    }

    boolean silent() {
        return Instant.now().isBefore(silentUntil);
    }

    synchronized int buffered() {
        return buffer.size();
    }

    long dropped() {
        return droppedRecords;
    }

    /**
     * Send as much of the buffer as the server acknowledges.
     *
     * @return records handed over and acknowledged in this call
     */
    synchronized int flush() {
        if (silent() || buffer.isEmpty()) {
            return 0;
        }
        int delivered = 0;
        try {
            connectIfNeeded();
            // Keep emptying while there is more: after a long gap one packet is not enough,
            // and a device that sent one packet per tick would never catch up.
            while (!buffer.isEmpty()) {
                List<AvlRecord> batch = new ArrayList<>(Math.min(maxRecordsPerPacket, buffer.size()));
                for (AvlRecord r : buffer) {
                    if (batch.size() == maxRecordsPerPacket) {
                        break;
                    }
                    batch.add(r);
                }
                out.write(Codec8Codec.encode(batch, codecId));
                out.flush();

                int acknowledged = in.readInt();
                if (acknowledged != batch.size()) {
                    // The server took a different number than was sent. Keep everything and
                    // retry: over-trusting the ACK is how records disappear silently.
                    log.warn("Device {}: sent {} record(s), server acknowledged {}; retrying later",
                            imei, batch.size(), acknowledged);
                    break;
                }
                for (int i = 0; i < batch.size(); i++) {
                    buffer.removeFirst();
                }
                delivered += batch.size();
            }
        } catch (IOException e) {
            // Coverage lost. Nothing is discarded — that is the whole contract.
            log.warn("Device {} lost its link ({}); {} record(s) held in buffer",
                    imei, e.getMessage(), buffer.size());
            closeQuietly();
        }
        return delivered;
    }

    private void connectIfNeeded() throws IOException {
        if (socket != null && socket.isConnected() && !socket.isClosed()) {
            return;
        }
        closeQuietly();

        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), timeoutMillis);
        s.setSoTimeout(timeoutMillis);
        s.setTcpNoDelay(true);

        OutputStream os = s.getOutputStream();
        DataInputStream is = new DataInputStream(s.getInputStream());

        // Handshake: two-byte length, IMEI in ASCII, then one byte back — 0x01 accepted.
        byte[] ascii = imei.getBytes(StandardCharsets.US_ASCII);
        os.write(new byte[]{(byte) (ascii.length >> 8), (byte) ascii.length});
        os.write(ascii);
        os.flush();

        int reply = is.read();
        if (reply != 0x01) {
            s.close();
            throw new IOException("Server refused IMEI " + imei + " (reply " + reply + ")");
        }
        this.socket = s;
        this.out = os;
        this.in = is;
    }

    void closeQuietly() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
            // Closing a socket that is already gone is not news.
        }
        socket = null;
        out = null;
        in = null;
    }
}
