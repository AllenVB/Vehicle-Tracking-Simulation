package com.fleet.vts.gateway.live;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Initial full snapshot of live positions so a freshly opened map is not blank
 * until the first per-second delta arrives. Live movement continues over STOMP
 * ({@code /topic/fleet/live}); this is only the seed. Requires a valid JWT.
 */
@RestController
@RequestMapping("/api/v1/live")
public class LiveController {

    private final LiveMapState state;

    public LiveController(LiveMapState state) {
        this.state = state;
    }

    @GetMapping("/positions")
    public List<Position> positions() {
        return state.snapshot();
    }
}
