package com.fleet.vts.gateway.track;

import com.fleet.vts.gateway.repository.VehicleMessageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The device-facing messaging endpoints are identified purely by the session token: no token means
 * no vehicle, so nothing can be read or sent. Driver-to-operator messages are free text (empty is
 * rejected), stored FROM_DRIVER and broadcast to the operators.
 */
class DriverMessageControllerTest {

    private final DriverSessionService sessions = mock(DriverSessionService.class);
    private final VehicleMessageRepository messages = mock(VehicleMessageRepository.class);
    private final SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
    private final DriverMessageController controller = new DriverMessageController(sessions, messages, messaging);

    @Test
    void inboxWithoutASessionIs401() {
        when(sessions.identify(null)).thenReturn(Optional.empty());

        ResponseEntity<List<Map<String, Object>>> res = controller.inbox(null);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(messages, never()).pullForDevice(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void inboxReturnsPulledMessagesOldestFirst() {
        when(sessions.identify("tok")).thenReturn(Optional.of(new DriverSessionService.Identity(7, "devA")));
        when(messages.pullForDevice(7)).thenReturn(new ArrayList<>(List.of(
                msg("2026-01-01T00:00:02Z", "ikinci"),
                msg("2026-01-01T00:00:01Z", "birinci"))));

        ResponseEntity<List<Map<String, Object>>> res = controller.inbox("tok");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).extracting(m -> m.get("body")).containsExactly("birinci", "ikinci");
    }

    @Test
    void emptyMessageIs400AndNothingStored() {
        when(sessions.identify("tok")).thenReturn(Optional.of(new DriverSessionService.Identity(7, "devA")));

        ResponseEntity<?> res = controller.message(new DriverMessageController.MessageRequest("   "), "tok");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(messages, never()).insertDeviceReply(org.mockito.ArgumentMatchers.anyLong(), anyString(), anyString());
        verify(messaging, never()).convertAndSend(anyString(), (Object) any());
    }

    @Test
    void messageWithoutSessionIs401() {
        when(sessions.identify(null)).thenReturn(Optional.empty());

        ResponseEntity<?> res = controller.message(new DriverMessageController.MessageRequest("yoldayim"), null);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(messages, never()).insertDeviceReply(org.mockito.ArgumentMatchers.anyLong(), anyString(), anyString());
    }

    @Test
    void validMessageIsStoredAndBroadcastToOperators() {
        when(sessions.identify("tok")).thenReturn(Optional.of(new DriverSessionService.Identity(7, "devA")));
        when(messages.plateOf(7)).thenReturn("06AT2130");

        ResponseEntity<?> res = controller.message(new DriverMessageController.MessageRequest("  yola ciktim  "), "tok");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(messages).insertDeviceReply(7, "MESAJ", "yola ciktim");   // trimmed
        verify(messaging).convertAndSend(eq("/topic/vehicle-messages"), (Object) any());
    }

    private static Map<String, Object> msg(String at, String body) {
        Map<String, Object> m = new HashMap<>();
        m.put("at", at);
        m.put("body", body);
        m.put("category", "GENEL");
        return m;
    }
}
