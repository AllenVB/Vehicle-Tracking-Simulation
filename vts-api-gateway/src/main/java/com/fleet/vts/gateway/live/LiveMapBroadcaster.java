package com.fleet.vts.gateway.live;

import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Pushes the live map once per second (never per event): a delta frame of only
 * the vehicles that changed, and to each session only the vehicles inside its
 * viewport.
 */
@Component
public class LiveMapBroadcaster {

    private final LiveMapState state;
    private final SimpMessagingTemplate messaging;

    public LiveMapBroadcaster(LiveMapState state, SimpMessagingTemplate messaging) {
        this.state = state;
        this.messaging = messaging;
    }

    @Scheduled(fixedRate = 1000)
    public void broadcast() {
        List<Position> changed = state.drainChanged();
        if (changed.isEmpty()) {
            return;
        }
        // Global delta for clients that did not register a viewport.
        messaging.convertAndSend("/topic/fleet/live", Map.of("vehicles", changed));

        // Per-session viewport-filtered delta.
        state.viewports().forEach((sessionId, bbox) -> {
            List<Position> inView = state.withinViewport(changed, bbox);
            if (!inView.isEmpty()) {
                messaging.convertAndSendToUser(sessionId, "/queue/fleet",
                        Map.of("vehicles", inView), sessionHeaders(sessionId));
            }
        });
    }

    private MessageHeaders sessionHeaders(String sessionId) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setSessionId(sessionId);
        accessor.setLeaveMutable(true);
        return accessor.getMessageHeaders();
    }
}
