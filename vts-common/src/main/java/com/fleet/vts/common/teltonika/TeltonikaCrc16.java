package com.fleet.vts.common.teltonika;

/**
 * CRC-16/IBM (ARC) over the data field of an AVL packet, as Teltonika specifies it:
 * reflected polynomial 0xA001, initial value 0, no final XOR.
 *
 * <p>It is sent as four bytes with the high two zeroed, which is why the return type is
 * {@code int} rather than {@code short} — sign-extending a 16-bit CRC into a comparison is
 * the classic way to make a correct packet look corrupt.
 */
public final class TeltonikaCrc16 {

    private static final int POLYNOMIAL = 0xA001;

    private TeltonikaCrc16() {
    }

    /** CRC over {@code data[offset, offset+length)}, as an unsigned 16-bit value. */
    public static int compute(byte[] data, int offset, int length) {
        int crc = 0;
        for (int i = offset; i < offset + length; i++) {
            crc ^= (data[i] & 0xFF);
            for (int bit = 0; bit < 8; bit++) {
                if ((crc & 1) != 0) {
                    crc = (crc >>> 1) ^ POLYNOMIAL;
                } else {
                    crc >>>= 1;
                }
            }
        }
        return crc & 0xFFFF;
    }
}
