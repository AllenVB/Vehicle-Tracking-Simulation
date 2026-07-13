package com.fleet.vts.gateway.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/** Validates credentials against {@code app_user} and issues a JWT with roles. */
@Service
public class AuthService {

    public record LoginResult(String token, long expiresIn, String username, List<String> roles) {
    }

    private record UserRow(Long id, Long tenantId, String passwordHash) {
    }

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public Optional<LoginResult> login(String username, String password) {
        List<UserRow> users = jdbc.query(
                "SELECT id, tenant_id, password_hash FROM app_user WHERE username = ? AND enabled = true",
                (rs, n) -> new UserRow(rs.getLong("id"), rs.getLong("tenant_id"), rs.getString("password_hash")),
                username);
        if (users.isEmpty() || !passwordEncoder.matches(password, users.get(0).passwordHash())) {
            return Optional.empty();
        }
        UserRow user = users.get(0);
        List<String> roles = jdbc.queryForList(
                "SELECT r.code FROM user_role ur JOIN role r ON r.id = ur.role_id WHERE ur.user_id = ?",
                String.class, user.id());
        String token = jwtService.issue(user.id(), user.tenantId(), username, roles);
        return Optional.of(new LoginResult(token, jwtService.ttlSeconds(), username, roles));
    }
}
