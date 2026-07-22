package com.fleet.vts.ingestion.adapter.in.tcp;

/** The two frame kinds a Teltonika session produces, in the order they occur. */
final class TeltonikaFrames {

    private TeltonikaFrames() {
    }

    /** The opening handshake: the device announces which IMEI is calling. */
    record Imei(String imei) {
    }

    /** A complete, length-delimited AVL packet, CRC not yet checked. */
    record Avl(byte[] packet) {
    }
}
