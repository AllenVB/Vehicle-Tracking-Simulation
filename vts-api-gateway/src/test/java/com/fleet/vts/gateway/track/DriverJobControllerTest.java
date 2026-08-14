package com.fleet.vts.gateway.track;

import com.fleet.vts.gateway.web.PositionReader;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Sürücünün görev durumunu ilerlettiği uç. Kimlik {@code X-Device-Session}'dan gelir; durum
 * sabit bir izin listesinden olmalı; ve güncelleme aracın kendi görevine kilitli — başka
 * aracın görevini işaretleme denemesi (SQL {@code vehicle_id} eşleşmez, 0 satır) 404 döner.
 */
@SuppressWarnings("unchecked")
class DriverJobControllerTest {

    private final DriverSessionService sessions = mock(DriverSessionService.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PositionReader positions = mock(PositionReader.class);
    private final DriverJobController controller = new DriverJobController(sessions, jdbc, positions);

    private void signedIn() {
        when(sessions.identify(anyString())).thenReturn(Optional.of(new DriverSessionService.Identity(5L, "dev")));
    }

    @Test
    void statusRejectsWithoutSession() {
        when(sessions.identify(any())).thenReturn(Optional.empty());

        ResponseEntity<?> res = controller.setStatus(1L, new DriverJobController.StatusRequest("EN_ROUTE"), null);

        assertThat(res.getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(jdbc);
    }

    @Test
    void statusRejectsUnknownValueWithoutTouchingDatabase() {
        signedIn();

        ResponseEntity<?> res = controller.setStatus(1L, new DriverJobController.StatusRequest("HACK"), "tok");

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(jdbc);
    }

    @Test
    void statusReturns404WhenJobIsNotThisVehiclesOrNotActive() {
        signedIn();
        when(jdbc.update(anyString(), any(), any(), any())).thenReturn(0);   // 0 satır = sahip değil / aktif değil

        ResponseEntity<?> res = controller.setStatus(9L, new DriverJobController.StatusRequest("ARRIVED"), "tok");

        assertThat(res.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void statusSucceedsForOwnedActiveJob() {
        signedIn();
        when(jdbc.update(anyString(), any(), any(), any())).thenReturn(1);

        ResponseEntity<?> res = controller.setStatus(1L, new DriverJobController.StatusRequest("done"), "tok");

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat((Map<String, Object>) res.getBody()).containsEntry("status", "DONE").containsEntry("jobId", 1L);
    }

    @Test
    void currentReturnsEmptyBodyWhenNoActiveJob() {
        signedIn();
        when(jdbc.queryForMap(anyString(), any())).thenThrow(new RuntimeException("no rows"));

        ResponseEntity<Map<String, Object>> res = controller.current("tok");

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).isEmpty();
        verifyNoInteractions(positions);
    }
}
