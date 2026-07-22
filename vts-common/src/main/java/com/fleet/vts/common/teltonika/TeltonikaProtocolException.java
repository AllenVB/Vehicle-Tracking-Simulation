package com.fleet.vts.common.teltonika;

/** A packet that does not parse: bad preamble, wrong length, failed CRC, unknown codec. */
public class TeltonikaProtocolException extends RuntimeException {

    public TeltonikaProtocolException(String message) {
        super(message);
    }
}
