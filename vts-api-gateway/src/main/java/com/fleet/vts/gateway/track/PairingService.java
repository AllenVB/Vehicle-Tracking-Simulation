package com.fleet.vts.gateway.track;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Device-flow pairing state (in-memory, short-lived), styled after GitHub's device
 * verification: scanning the QR is not enough to get in.
 *
 * <p>The desktop creates a session and shows the QR. Only when the phone actually scans it
 * ({@code scan}) is a 2-digit code minted and revealed <em>on the desktop</em>. The human then
 * types that code on the phone ({@code verify}); the phone never receives the code from the
 * server, so completing the flow proves the person is looking at the desktop screen — a scanned
 * QR alone cannot confirm. Two id's keep this honest: {@code sessionId} is desktop-private (used
 * to read the code back); {@code publicId} travels in the QR to the phone (used to scan/verify)
 * and never exposes the code.
 */
@Service
public class PairingService {

    private static final Duration TTL = Duration.ofMinutes(15);

    public enum Status { WAITING, SCANNED, CONFIRMED }

    private final Map<String, Session> bySession = new ConcurrentHashMap<>();
    private final Map<String, Session> byPublic = new ConcurrentHashMap<>();
    private volatile String publicUrl = "";

    public Session create() {
        purge();
        Session s = new Session();
        bySession.put(s.sessionId, s);
        byPublic.put(s.publicId, s);
        return s;
    }

    /** Phone scanned the QR: mint the code (revealed to the desktop only) and move to SCANNED. */
    public Optional<Session> scan(String publicId) {
        Session s = alive(byPublic.get(publicId));
        if (s == null) {
            return Optional.empty();
        }
        s.markScanned();
        return Optional.of(s);
    }

    public Optional<Session> bySessionId(String sessionId) {
        return Optional.ofNullable(alive(bySession.get(sessionId)));
    }

    /** Phone submitted the code the human read off the desktop; true when it matches. */
    public boolean verify(String publicId, String code) {
        Session s = alive(byPublic.get(publicId));
        if (s == null || s.code == null || !s.code.equals(code)) {
            return false;
        }
        s.status = Status.CONFIRMED;
        return true;
    }

    public boolean isConfirmed(String publicId) {
        Session s = alive(byPublic.get(publicId));
        return s != null && s.status == Status.CONFIRMED;
    }

    public String getPublicUrl() {
        return publicUrl;
    }

    public void setPublicUrl(String url) {
        this.publicUrl = url == null ? "" : url.trim();
    }

    private Session alive(Session s) {
        return (s == null || s.expired()) ? null : s;
    }

    private void purge() {
        bySession.values().removeIf(Session::expired);
        byPublic.values().removeIf(Session::expired);
    }

    public static final class Session {
        final String sessionId = UUID.randomUUID().toString();
        final String publicId = UUID.randomUUID().toString();
        volatile String code;                 // null until scanned
        volatile Status status = Status.WAITING;
        final Instant createdAt = Instant.now();

        void markScanned() {
            if (status == Status.WAITING) {
                code = String.format("%02d", ThreadLocalRandom.current().nextInt(0, 100));
                status = Status.SCANNED;
            }
        }

        boolean expired() {
            return createdAt.plus(TTL).isBefore(Instant.now());
        }

        public String sessionId() { return sessionId; }
        public String publicId() { return publicId; }
        public String code() { return code; }
        public Status status() { return status; }

        /** Desktop-facing view: code only once scanned. */
        public Map<String, Object> desktopView() {
            return status == Status.WAITING
                    ? Map.of("status", status.name())
                    : Map.of("status", status.name(), "code", code);
        }
    }
}
