package com.fleet.vts.gateway.track;

import com.fleet.vts.gateway.repository.VehicleMessageRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The device-facing side of driver messaging (public, like the rest of {@code /api/v1/track}).
 * The phone is identified by its {@code X-Device-Session} token — never a plate or vehicleId in
 * the URL — so one driver can only ever read or answer for the vehicle it actually holds.
 *
 * <ul>
 *   <li>{@code GET /messages} — pull undelivered operator→driver messages (marked delivered as
 *       they are returned); the phone reads them aloud with text-to-speech.</li>
 *   <li>{@code GET /reply-options} — the fixed catalogue of one-tap replies.</li>
 *   <li>{@code POST /reply} — record a one-tap reply and broadcast it to the operators.</li>
 * </ul>
 * Replies are a fixed catalogue, not free text: a driver taps a big button while driving, and an
 * open text field on the road is both unsafe and a needless injection surface.
 */
@RestController
@RequestMapping("/api/v1/track")
public class DriverMessageController {

    /** Fixed one-tap reply catalogue (code → label shown on the button and stored/broadcast). */
    private static final Map<String, String> REPLIES = new LinkedHashMap<>();

    static {
        REPLIES.put("YOLDA", "Yoldayım");
        REPLIES.put("MUSTERI", "Müşterideyim");
        REPLIES.put("MOLA", "Moladayım");
        REPLIES.put("TESLIM", "Teslim tamamlandı");
        REPLIES.put("YARDIM", "Yardım gerekiyor");
    }

    private final DriverSessionService sessions;
    private final VehicleMessageRepository messages;
    private final SimpMessagingTemplate messaging;

    public DriverMessageController(DriverSessionService sessions, VehicleMessageRepository messages,
                                   SimpMessagingTemplate messaging) {
        this.sessions = sessions;
        this.messages = messages;
        this.messaging = messaging;
    }

    @GetMapping("/messages")
    public ResponseEntity<List<Map<String, Object>>> inbox(
            @RequestHeader(value = "X-Device-Session", required = false) String session) {
        Optional<DriverSessionService.Identity> id = sessions.identify(session);
        if (id.isEmpty()) {
            return ResponseEntity.status(401).build();
        }
        List<Map<String, Object>> pulled = messages.pullForDevice(id.get().vehicleId());
        pulled.sort((a, b) -> String.valueOf(a.get("at")).compareTo(String.valueOf(b.get("at"))));
        return ResponseEntity.ok(pulled);
    }

    /**
     * Full conversation history for the driver app (both directions, oldest first). Loaded once when
     * the app opens; marks the operator messages as seen so the {@code /messages} poll won't read
     * them again (and won't read them aloud a second time).
     */
    @GetMapping("/history")
    public ResponseEntity<List<Map<String, Object>>> history(
            @RequestHeader(value = "X-Device-Session", required = false) String session) {
        Optional<DriverSessionService.Identity> id = sessions.identify(session);
        if (id.isEmpty()) {
            return ResponseEntity.status(401).build();
        }
        long vehicleId = id.get().vehicleId();
        List<Map<String, Object>> history = messages.conversation(vehicleId);
        messages.markDelivered(vehicleId);
        return ResponseEntity.ok(history);
    }

    @GetMapping("/reply-options")
    public List<Map<String, String>> replyOptions() {
        return REPLIES.entrySet().stream()
                .map(e -> Map.of("code", e.getKey(), "label", e.getValue()))
                .toList();
    }

    public record MessageRequest(String text) {
    }

    /**
     * Driver to operator. Free text (not a fixed catalogue): the quick-reply chips are only a
     * convenience that fill this same field. Trimmed, length-capped, and delivered to the operators
     * on {@code /topic/vehicle-messages} like any other event.
     */
    @PostMapping("/message")
    public ResponseEntity<?> message(@RequestBody MessageRequest req,
                                     @RequestHeader(value = "X-Device-Session", required = false) String session) {
        Optional<DriverSessionService.Identity> id = sessions.identify(session);
        if (id.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "NO_SESSION"));
        }
        String text = req == null || req.text() == null ? "" : req.text().trim();
        if (text.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "EMPTY"));
        }
        if (text.length() > MAX_TEXT) {
            text = text.substring(0, MAX_TEXT);
        }
        long vehicleId = id.get().vehicleId();
        messages.insertDeviceReply(vehicleId, "MESAJ", text);

        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("vehicleId", vehicleId);
        msg.put("plate", messages.plateOf(vehicleId));
        msg.put("category", "MESAJ");
        msg.put("body", text);
        msg.put("direction", "FROM_DRIVER");
        msg.put("at", Instant.now().toString());
        messaging.convertAndSend("/topic/vehicle-messages", msg);   // operatörlere anlık bildirim
        return ResponseEntity.ok(msg);
    }

    private static final int MAX_TEXT = 500;
}
