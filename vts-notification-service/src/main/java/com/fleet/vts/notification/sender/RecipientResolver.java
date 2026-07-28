package com.fleet.vts.notification.sender;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Resolves a user's e-mail address from {@code app_user} for a notification
 * recipient, cached in Caffeine (TTL 60s) to spare the DB one lookup per
 * violation. Mirrors {@link com.fleet.vts.notification.service.PreferenceService}'s
 * caching approach. A missing/unknown user yields {@link Optional#empty()}.
 */
@Component
public class RecipientResolver {

    private final JdbcTemplate jdbc;
    private final Cache<Long, Optional<String>> byUser = Caffeine.newBuilder()
            .maximumSize(10_000).expireAfterWrite(Duration.ofSeconds(60)).build();

    public RecipientResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> emailFor(Long userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return byUser.get(userId, this::load);
    }

    private Optional<String> load(Long userId) {
        try {
            return Optional.ofNullable(
                    jdbc.queryForObject("SELECT email FROM app_user WHERE id = ?",
                            String.class, userId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }
}
