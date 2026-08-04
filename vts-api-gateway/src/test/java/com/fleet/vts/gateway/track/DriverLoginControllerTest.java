package com.fleet.vts.gateway.track;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The driver sign-in orchestration: reject incomplete requests (400), keep all three credential
 * failures indistinguishable (401), block a login while another device holds the vehicle (409),
 * and on success hand back the IMEI to stream under plus the session token.
 */
class DriverLoginControllerTest {

    private final DriverAuthService auth = mock(DriverAuthService.class);
    private final DriverSessionService sessions = mock(DriverSessionService.class);
    private final DriverLoginController controller = new DriverLoginController(auth, sessions);

    private static final DriverAuthService.DriverIdentity ID =
            new DriverAuthService.DriverIdentity(111L, 1L, "904184072081054", "06AT2130", "Citroen", "C4");

    private static DriverLoginController.LoginRequest req(String plate, String model, String pw, String dev) {
        return new DriverLoginController.LoginRequest(plate, model, pw, dev);
    }

    @Test
    void missingFieldsAre400() {
        ResponseEntity<?> res = controller.login(req("06AT2130", "C4", "  ", "devA"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void badCredentialsAre401() {
        when(auth.login("06AT2130", "C4", "1234", "devA")).thenReturn(Optional.empty());
        ResponseEntity<?> res = controller.login(req("06AT2130", "C4", "1234", "devA"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void vehicleAlreadyInUseIs409() {
        when(auth.login("06AT2130", "C4", "1234", "devA")).thenReturn(Optional.of(ID));
        when(sessions.tryLogin(111L, "devA")).thenReturn(Optional.empty());
        ResponseEntity<?> res = controller.login(req("06AT2130", "C4", "1234", "devA"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void successReturnsImeiAndSessionToken() {
        when(auth.login("06AT2130", "C4", "1234", "devA")).thenReturn(Optional.of(ID));
        when(sessions.tryLogin(111L, "devA")).thenReturn(Optional.of("tok-123"));

        ResponseEntity<?> res = controller.login(req("06AT2130", "C4", "1234", "devA"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) res.getBody();
        assertThat(body)
                .containsEntry("imei", "904184072081054")
                .containsEntry("vehicleId", 111L)
                .containsEntry("sessionToken", "tok-123");
    }

    @Test
    void logoutDelegatesToSessionService() {
        controller.logout(Map.of("sessionToken", "tok-123"));
        verify(sessions).logout("tok-123");
    }
}
