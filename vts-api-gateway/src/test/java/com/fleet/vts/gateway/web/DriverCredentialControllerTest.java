package com.fleet.vts.gateway.web;

import com.fleet.vts.gateway.track.DriverSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Admin driver-credential management is tenant-scoped: setting a password or freeing a session for
 * a vehicle that is not the caller's tenant returns 404 and touches nothing. A valid request hashes
 * the password before it is stored, and force-logout evicts the vehicle's active session.
 */
class DriverCredentialControllerTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final DriverSessionService sessions = mock(DriverSessionService.class);
    private final DriverCredentialController controller = new DriverCredentialController(jdbc, encoder, sessions);

    private static final Jwt JWT = Jwt.withTokenValue("t").header("alg", "none").claim("tenantId", 1L).build();

    @SuppressWarnings("unchecked")
    private void owns(boolean owned) {
        doReturn(owned ? List.of(9L) : List.of())
                .when(jdbc).query(anyString(), any(RowMapper.class), eq(9L), eq(1L));
    }

    @Test
    void setsHashedPasswordWhenVehicleBelongsToTenant() {
        owns(true);
        when(encoder.encode("gizli12")).thenReturn("$2a$hash");

        ResponseEntity<?> res = controller.setPassword(JWT, 9L, new DriverCredentialController.CredentialRequest("gizli12"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(jdbc).update(anyString(), eq(1L), eq(9L), eq("$2a$hash"), eq("gizli12"));   // hash + plaintext
    }

    @Test
    @SuppressWarnings("unchecked")
    void revealsThePlaintextForAnOwnedVehicle() {
        owns(true);
        doReturn(List.of("gizli12")).when(jdbc).query(anyString(), any(RowMapper.class), eq(9L));

        ResponseEntity<Map<String, Object>> res = controller.getPassword(JWT, 9L);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsEntry("password", "gizli12");
    }

    @Test
    void returns404AndStoresNothingForAnotherTenantsVehicle() {
        owns(false);

        ResponseEntity<?> res = controller.setPassword(JWT, 9L, new DriverCredentialController.CredentialRequest("gizli12"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(encoder, never()).encode(anyString());
        verify(jdbc, never()).update(anyString(), any(), any(), any());
    }

    @Test
    void forceLogoutEvictsTheVehiclesSession() {
        owns(true);

        ResponseEntity<?> res = controller.forceLogout(JWT, 9L);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(sessions).evict(9L);
    }

    @Test
    void forceLogoutOnAnotherTenantsVehicleIs404() {
        owns(false);

        ResponseEntity<?> res = controller.forceLogout(JWT, 9L);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(sessions, never()).evict(9L);
    }
}
