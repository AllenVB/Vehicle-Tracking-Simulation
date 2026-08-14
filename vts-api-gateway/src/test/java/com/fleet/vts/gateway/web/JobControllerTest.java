package com.fleet.vts.gateway.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Görev atama (admin). Eksik alan ve geçersiz hedef konum, veritabanına hiç dokunmadan
 * reddedilir; başka tenant'ın aracına görev atanamaz (araç sayımı 0 → 404); geçerli istek
 * yeni görev id'si döndürür; iptal, aktif olmayan/başka tenant görevinde 404 verir.
 */
class JobControllerTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PositionReader positions = mock(PositionReader.class);
    private final JobController controller = new JobController(jdbc, positions);

    private static final Jwt JWT = Jwt.withTokenValue("t").header("alg", "none")
            .subject("admin").claim("tenantId", 1L).build();

    @Test
    void rejectsMissingFieldsWithoutTouchingDatabase() {
        ResponseEntity<Map<String, Object>> res = controller.create(JWT,
                new JobController.CreateRequest(7L, "  ", "Depo", 41.0, 29.0));   // boş başlık

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsOutOfRangeDestinationWithoutTouchingDatabase() {
        ResponseEntity<Map<String, Object>> res = controller.create(JWT,
                new JobController.CreateRequest(7L, "Teslimat", "Depo", 200.0, 29.0));

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(jdbc);
    }

    @Test
    void returns404WhenVehicleIsNotInTenant() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(0);

        ResponseEntity<Map<String, Object>> res = controller.create(JWT,
                new JobController.CreateRequest(7L, "Teslimat", "Depo", 41.0, 29.0));

        assertThat(res.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void createsJobAndReturnsItsId() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(1);   // araç tenant'ta
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any(), any(), any(), any(), any()))
                .thenReturn(99L);   // INSERT ... RETURNING id

        ResponseEntity<Map<String, Object>> res = controller.create(JWT,
                new JobController.CreateRequest(7L, "Teslimat", "Kadıköy", 40.99, 29.02));

        assertThat(res.getStatusCode().value()).isEqualTo(201);
        assertThat(res.getBody()).containsEntry("jobId", 99L);
    }

    @Test
    void cancelReturns404WhenNothingActiveMatched() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);

        assertThat(controller.cancel(JWT, 5L).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void cancelReturns204WhenActiveJobCancelled() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        assertThat(controller.cancel(JWT, 5L).getStatusCode().value()).isEqualTo(204);
    }
}
