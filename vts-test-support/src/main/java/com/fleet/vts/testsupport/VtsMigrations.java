package com.fleet.vts.testsupport;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Applies the gateway-owned Flyway migrations to an arbitrary datasource.
 *
 * <p>The gateway is the single schema owner, but every service that reads the schema needs
 * it present in its own tests. Rather than copying the SQL (which would drift on the first
 * migration nobody remembered to copy), the migrations are read from the gateway module on
 * disk. That is only possible in a monorepo, which this is.
 */
public final class VtsMigrations {

    /** Path of the gateway migration folder, relative to the repository root. */
    private static final String MIGRATION_PATH = "vts-api-gateway/src/main/resources/db/migration";

    /**
     * Placeholder values from the {@code dev} profile. The same SQL is parameterised so that
     * chunk interval and retention can vary per profile; tests use the dev numbers.
     */
    private static final Map<String, String> PLACEHOLDERS = Map.of(
            "telemetry_chunk_interval", "1 day",
            "retention_days", "30");

    private VtsMigrations() {
    }

    /** Runs every migration against {@code jdbcUrl}; returns how many were applied. */
    public static int migrate(String jdbcUrl, String user, String password) {
        MigrateResult result = Flyway.configure()
                .dataSource(jdbcUrl, user, password)
                .locations("filesystem:" + migrationDir())
                .placeholders(PLACEHOLDERS)
                .baselineOnMigrate(false)
                .load()
                .migrate();
        return result.migrationsExecuted;
    }

    /** Absolute path of the migration folder, found by walking up to the repository root. */
    public static Path migrationDir() {
        // Surefire/Failsafe run with the module directory as CWD, so the repository root is
        // one level up; an IDE may run from the root itself. Walking up covers both.
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve(MIGRATION_PATH);
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "Flyway migrations not found; expected <repo-root>/" + MIGRATION_PATH
                        + " searching upwards from " + Path.of("").toAbsolutePath());
    }

    /** Number of versioned migration files on disk — what a migration run must apply. */
    public static long migrationFileCount() {
        try (var files = Files.list(migrationDir())) {
            return files.filter(p -> p.getFileName().toString().endsWith(".sql")).count();
        } catch (Exception e) {
            throw new IllegalStateException("Could not list migrations", e);
        }
    }
}
