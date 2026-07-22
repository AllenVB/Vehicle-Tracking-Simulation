package com.fleet.vts.ingestion.adapter.out.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Moves a command row through its states.
 *
 * <p>The gateway creates the row and ingestion is the only thing that can advance it, because
 * only ingestion knows whether a socket was there. Every transition is guarded on the state it
 * expects to find, so a late response cannot resurrect a command that already timed out and a
 * retry cannot count as a second answer.
 */
@Component
public class DeviceCommandStore {

    private final JdbcTemplate jdbc;

    public DeviceCommandStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void markSent(long commandId) {
        jdbc.update("UPDATE device_command SET status = 'SENT', sent_at = now() "
                + "WHERE id = ? AND status = 'PENDING'", commandId);
    }

    public void markAnswered(long commandId, String response) {
        jdbc.update("UPDATE device_command SET status = 'ANSWERED', response = ?, executed_at = now() "
                + "WHERE id = ? AND status = 'SENT'", response, commandId);
    }

    public void markFailed(long commandId, String reason) {
        jdbc.update("UPDATE device_command SET status = 'FAILED', response = ?, executed_at = now() "
                + "WHERE id = ? AND status IN ('PENDING', 'SENT')", reason, commandId);
    }

    /**
     * Close out commands nobody answered, distinguishing the two ways that happens.
     *
     * <p>{@code SENT} with no answer means a device took the instruction and said nothing.
     * {@code PENDING} past the deadline means no instance ever held a session for it — the
     * device is offline. The operator needs to tell those apart, so they get different states
     * rather than one shared "failed".
     *
     * @return how many rows were closed out
     */
    public int expireStale(int timeoutSeconds) {
        int timedOut = jdbc.update("""
                UPDATE device_command SET status = 'TIMEOUT', executed_at = now()
                WHERE status = 'SENT' AND sent_at < now() - make_interval(secs => ?)
                """, (double) timeoutSeconds);

        int undelivered = jdbc.update("""
                UPDATE device_command
                   SET status = 'NO_SESSION', response = 'Cihaz bağlı değil', executed_at = now()
                WHERE status = 'PENDING' AND issued_at < now() - make_interval(secs => ?)
                """, (double) timeoutSeconds);

        return timedOut + undelivered;
    }
}
