package com.fleet.vts.gateway.live;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory live-map state. Holds the latest position per vehicle, the set of
 * vehicles that changed since the last broadcast (for delta frames), and each
 * session's viewport. This is what lets the gateway push once per second — never
 * per event.
 */
@Component
public class LiveMapState {

    private final Map<Long, Position> latest = new ConcurrentHashMap<>();
    private final Set<Long> changed = ConcurrentHashMap.newKeySet();
    private final Map<String, Bbox> viewports = new ConcurrentHashMap<>();

    public void update(Position position) {
        latest.put(position.vehicleId(), position);
        changed.add(position.vehicleId());
    }

    /** Return the positions changed since the last call and reset the change set. */
    public List<Position> drainChanged() {
        List<Position> result = new ArrayList<>();
        for (Long id : Set.copyOf(changed)) {
            changed.remove(id);
            Position p = latest.get(id);
            if (p != null) {
                result.add(p);
            }
        }
        return result;
    }

    public void setViewport(String sessionId, Bbox bbox) {
        viewports.put(sessionId, bbox);
    }

    public void removeSession(String sessionId) {
        viewports.remove(sessionId);
    }

    public Map<String, Bbox> viewports() {
        return viewports;
    }

    /** Positions from {@code changed} that fall inside {@code bbox}. */
    public List<Position> withinViewport(List<Position> changed, Bbox bbox) {
        List<Position> result = new ArrayList<>();
        for (Position p : changed) {
            if (bbox.contains(p)) {
                result.add(p);
            }
        }
        return result;
    }
}
