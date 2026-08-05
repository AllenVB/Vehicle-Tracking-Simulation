package com.fleet.vts.gateway.web;

import com.fleet.vts.gateway.repository.BroadcastMessageRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A broadcast is persisted once and then fanned out to {@code /topic/broadcast} carrying the
 * generated id, sender and clipped body; a blank body is rejected without touching the store or
 * the broker; and the list endpoint is tenant-scoped.
 */
class BroadcastControllerTest {

    private final BroadcastMessageRepository repo = mock(BroadcastMessageRepository.class);
    private final SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
    private final BroadcastController controller = new BroadcastController(repo, messaging);

    private static final Jwt JWT = Jwt.withTokenValue("t").header("alg", "none")
            .subject("admin").claim("tenantId", 1L).build();

    @Test
    void persistsOnceAndFansOutToTheBroadcastTopic() {
        when(repo.insert(eq(1L), eq("admin"), eq("Duyuru"), eq("Yarın bakım var"))).thenReturn(7L);

        ResponseEntity<Map<String, Object>> res =
                controller.send(JWT, new BroadcastController.BroadcastRequest("Duyuru", "Yarın bakım var"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody())
                .containsEntry("id", 7L)
                .containsEntry("sender", "admin")
                .containsEntry("body", "Yarın bakım var");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(messaging).convertAndSend(eq(BroadcastController.TOPIC), payload.capture());
        assertThat(payload.getValue()).containsEntry("id", 7L).containsEntry("title", "Duyuru");
    }

    @Test
    void rejectsBlankBodyWithoutStoringOrPublishing() {
        ResponseEntity<Map<String, Object>> res =
                controller.send(JWT, new BroadcastController.BroadcastRequest("Duyuru", "   "));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(repo, never()).insert(anyLong(), anyString(), any(), anyString());
        verify(messaging, never()).convertAndSend(anyString(), (Object) any());
    }

    @Test
    void clipsAnOverLongBodyToTheColumnLimit() {
        String longBody = "x".repeat(1200);
        when(repo.insert(anyLong(), anyString(), any(), anyString())).thenReturn(1L);

        controller.send(JWT, new BroadcastController.BroadcastRequest(null, longBody));

        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        verify(repo).insert(eq(1L), eq("admin"), eq(null), stored.capture());
        assertThat(stored.getValue()).hasSize(1000);
    }

    @Test
    void listIsTenantScoped() {
        when(repo.recent(1L)).thenReturn(List.of(Map.of("id", 1L, "body", "merhaba")));

        List<Map<String, Object>> out = controller.list(JWT);

        assertThat(out).hasSize(1);
        verify(repo).recent(1L);
    }
}
