package com.fleet.vts.iettfeed.mapping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Maps each distinct real bus (İETT {@code kapino}) to one seeded device IMEI
 * ({@code 000000000000001}–{@code 000000000000100}) so its real position rides a
 * known vehicle. Assignment is stable for the process lifetime; once all seeded
 * IMEIs are taken, further buses are dropped (returns {@code null}) and reported
 * once — never silently truncated.
 */
@Component
public class ImeiAssigner {

    private static final Logger log = LoggerFactory.getLogger(ImeiAssigner.class);

    private final int capacity;
    private final ConcurrentMap<String, String> assigned = new ConcurrentHashMap<>();
    private int next = 1;
    private boolean capacityWarned = false;

    public ImeiAssigner(com.fleet.vts.iettfeed.config.IettProperties props) {
        this.capacity = props.getMaxVehicles();
    }

    /** Returns the IMEI for this bus, assigning a new one on first sight.
     *  {@code null} when capacity is exhausted. */
    public synchronized String assign(String kapino) {
        String existing = assigned.get(kapino);
        if (existing != null) {
            return existing;
        }
        if (next > capacity) {
            if (!capacityWarned) {
                log.warn("IMEI kapasitesi ({}) doldu; fazladan otobüsler atlanıyor", capacity);
                capacityWarned = true;
            }
            return null;
        }
        String imei = String.format("%015d", next++);
        assigned.put(kapino, imei);
        return imei;
    }

    /** Number of buses currently mapped to an IMEI. */
    public int size() {
        return assigned.size();
    }
}
