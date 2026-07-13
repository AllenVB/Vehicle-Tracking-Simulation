package com.fleet.vts.gateway.web;

import com.fleet.vts.gateway.security.AuthService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Authentication endpoint: exchange username/password for a JWT. */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record LoginResponse(String token, String tokenType, long expiresIn,
                                String username, List<String> roles) {
    }

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@jakarta.validation.Valid @RequestBody LoginRequest request) {
        return authService.login(request.username(), request.password())
                .map(r -> ResponseEntity.ok(new LoginResponse(
                        r.token(), "Bearer", r.expiresIn(), r.username(), r.roles())))
                .orElseGet(() -> ResponseEntity.status(401).build());
    }
}
