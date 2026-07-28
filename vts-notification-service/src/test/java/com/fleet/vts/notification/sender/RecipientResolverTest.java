package com.fleet.vts.notification.sender;

import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** userId -> email lookup, empty-user handling and Caffeine caching. */
class RecipientResolverTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final RecipientResolver resolver = new RecipientResolver(jdbc);

    @Test
    void resolvesEmailForKnownUser() {
        when(jdbc.queryForObject(any(), eq(String.class), eq(9L))).thenReturn("admin@demo.local");

        assertThat(resolver.emailFor(9L)).contains("admin@demo.local");
    }

    @Test
    void emptyForUnknownUser() {
        when(jdbc.queryForObject(any(), eq(String.class), eq(404L)))
                .thenThrow(new EmptyResultDataAccessException(1));

        assertThat(resolver.emailFor(404L)).isEmpty();
    }

    @Test
    void emptyForNullUserWithoutQuery() {
        assertThat(resolver.emailFor(null)).isEmpty();
        verify(jdbc, times(0)).queryForObject(any(), eq(String.class), any(Object[].class));
    }

    @Test
    void cachesRepeatedLookups() {
        when(jdbc.queryForObject(any(), eq(String.class), eq(9L))).thenReturn("admin@demo.local");

        resolver.emailFor(9L);
        resolver.emailFor(9L);

        verify(jdbc, times(1)).queryForObject(any(), eq(String.class), eq(9L));
    }
}
