package com.fleet.vts.gateway.track;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Sefer öncesi araç kontrolü (DVIR) sürücü ucu. Kimlik {@code X-Device-Session}'dan çözülür;
 * genel sonuç ({@code OK}/{@code DEFECT}) istemciye güvenilmeden sunucuda hesaplanır (herhangi
 * bir madde kusurluysa DEFECT); ve yalnızca bilinen madde/değerler kabul edilir.
 */
@SuppressWarnings("unchecked")
class DriverInspectionControllerTest {

    private final DriverSessionService sessions = mock(DriverSessionService.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final DriverInspectionController controller =
            new DriverInspectionController(sessions, jdbc, new ObjectMapper());

    private void signedIn() {
        when(sessions.identify(anyString())).thenReturn(Optional.of(new DriverSessionService.Identity(5L, "dev")));
        when(jdbc.queryForObject(anyString(), eq(Long.class), any())).thenReturn(1L);   // tenant lookup
    }

    private static Map<String, String> items(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void rejectsWithoutSessionAndTouchesNoDatabase() {
        when(sessions.identify(any())).thenReturn(Optional.empty());

        ResponseEntity<?> res = controller.submit(
                new DriverInspectionController.InspectionRequest(items("tires", "ok"), null), null);

        assertThat(res.getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsEmptyItemsWithoutTouchingDatabase() {
        when(sessions.identify(anyString())).thenReturn(Optional.of(new DriverSessionService.Identity(5L, "dev")));

        ResponseEntity<?> res = controller.submit(
                new DriverInspectionController.InspectionRequest(Map.of(), null), "tok");

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsWhenOnlyUnknownItemsSurvive() {
        when(sessions.identify(anyString())).thenReturn(Optional.of(new DriverSessionService.Identity(5L, "dev")));

        ResponseEntity<?> res = controller.submit(
                new DriverInspectionController.InspectionRequest(items("wings", "ok", "tires", "maybe"), null), "tok");

        assertThat(res.getStatusCode().value()).isEqualTo(400);   // hiç geçerli madde yok
        verifyNoInteractions(jdbc);
    }

    @Test
    void allOkYieldsOverallOk() {
        signedIn();

        ResponseEntity<?> res = controller.submit(new DriverInspectionController.InspectionRequest(
                items("tires", "ok", "brakes", "ok", "lights", "ok"), "temiz"), "tok");

        assertThat(res.getStatusCode().value()).isEqualTo(201);
        assertThat((Map<String, Object>) res.getBody()).containsEntry("overall", "OK");
    }

    @Test
    void anyDefectYieldsOverallDefect() {
        signedIn();

        ResponseEntity<?> res = controller.submit(new DriverInspectionController.InspectionRequest(
                items("tires", "ok", "brakes", "defect", "lights", "ok"), "fren yumuşak"), "tok");

        assertThat(res.getStatusCode().value()).isEqualTo(201);
        assertThat((Map<String, Object>) res.getBody()).containsEntry("overall", "DEFECT");
    }
}
