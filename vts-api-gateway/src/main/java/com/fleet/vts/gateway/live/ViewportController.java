package com.fleet.vts.gateway.live;

import org.springframework.context.event.EventListener;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/** Receives each client's viewport (bbox) and cleans it up on disconnect. */
@Controller
public class ViewportController {

    private final LiveMapState state;

    public ViewportController(LiveMapState state) {
        this.state = state;
    }

    @MessageMapping("/viewport")
    public void updateViewport(@Payload Bbox bbox, SimpMessageHeaderAccessor headers) {
        state.setViewport(headers.getSessionId(), bbox);
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        state.removeSession(event.getSessionId());
    }
}
