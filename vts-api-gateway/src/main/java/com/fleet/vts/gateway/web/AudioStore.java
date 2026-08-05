package com.fleet.vts.gateway.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Voice-message file storage. Audio lives as plain files under a volume ({@code vts.audio.dir}),
 * never in the database — a message row keeps only the reference, so the message table stays small
 * and cheap to query, and a weekly retention job just deletes old files. Refs are server-generated
 * UUIDs, validated on every read/delete, so a client can never traverse out of the audio directory.
 */
@Service
public class AudioStore {

    /** A voice note is a short clip; anything larger is rejected before it is stored. */
    public static final int MAX_BYTES = 1024 * 1024;   // 1 MB
    private static final String UUID_RE =
            "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}";

    private final Path dir;

    public AudioStore(@Value("${vts.audio.dir:/data/audio}") String dir) {
        this.dir = Path.of(dir);
        try {
            Files.createDirectories(this.dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create audio dir " + dir, e);
        }
    }

    /** Store bytes and return the new reference (a UUID used as the file name). */
    public String save(byte[] data) throws IOException {
        String ref = UUID.randomUUID().toString();
        Files.write(dir.resolve(ref), data);
        return ref;
    }

    /** Bytes for a ref, or null if the ref is malformed (rejected) or the file is missing. */
    public byte[] load(String ref) throws IOException {
        if (ref == null || !ref.matches(UUID_RE)) {
            return null;
        }
        Path p = dir.resolve(ref);
        return Files.exists(p) ? Files.readAllBytes(p) : null;
    }

    public void delete(String ref) {
        if (ref == null || !ref.matches(UUID_RE)) {
            return;
        }
        try {
            Files.deleteIfExists(dir.resolve(ref));
        } catch (IOException e) {
            // best-effort cleanup; the row is gone either way
        }
    }
}
