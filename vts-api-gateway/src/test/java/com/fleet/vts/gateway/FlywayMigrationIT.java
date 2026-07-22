package com.fleet.vts.gateway;

import com.fleet.vts.testsupport.VtsContainers;
import com.fleet.vts.testsupport.VtsMigrations;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Applies every migration to a database that has never seen them.
 *
 * <p>The gap this closes is specific. V22 widened {@code rule_assignment.scope_type} from
 * VARCHAR(10) to VARCHAR(20) and then inserted the 12-character value {@code VEHICLE_TYPE}.
 * Without the widening the migration fails — but only on a database where V5 created the
 * narrow column, i.e. on a clean one. The running dev database already had the column
 * widened by an earlier attempt, so every local check passed and the failure only appeared
 * on deploy. Nothing in the build ever created a clean schema.
 *
 * <p>Hence the deliberately un-reused container: a shared, already-migrated one would report
 * "0 migrations applied" and pass while proving nothing.
 */
class FlywayMigrationIT {

    @SuppressWarnings("resource") // closed in @AfterAll
    private static final PostgreSQLContainer<?> POSTGRES = VtsContainers.freshPostgres();

    @BeforeAll
    static void start() {
        POSTGRES.start();
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @Test
    void everyMigrationAppliesToACleanDatabase() throws Exception {
        long onDisk = VtsMigrations.migrationFileCount();

        int applied = VtsMigrations.migrate(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());

        // Every file on disk must run: a count lower than the folder means Flyway skipped
        // something (out-of-order version, baseline) rather than that the schema is fine.
        assertThat(applied)
                .as("migrations applied to a clean database")
                .isEqualTo((int) onDisk);

        assertThat(rowCount("SELECT count(*) FROM flyway_schema_history WHERE success = false"))
                .as("failed migrations recorded in flyway_schema_history")
                .isZero();

        // Spot-checks on what the schema is supposed to be, not just that SQL ran:
        // the telemetry hypertable, PostGIS, and the V22 column width that used to overflow.
        assertThat(rowCount("SELECT count(*) FROM timescaledb_information.hypertables "
                + "WHERE hypertable_name = 'telemetry'"))
                .as("telemetry is a hypertable")
                .isOne();

        assertThat(rowCount("SELECT count(*) FROM pg_extension WHERE extname = 'postgis'"))
                .as("PostGIS installed")
                .isOne();

        assertThat(scalar("SELECT character_maximum_length FROM information_schema.columns "
                + "WHERE table_name = 'rule_assignment' AND column_name = 'scope_type'"))
                .as("rule_assignment.scope_type must hold 'VEHICLE_TYPE' (12 chars)")
                .isEqualTo(20);
    }

    private long rowCount(String sql) throws Exception {
        return scalar(sql);
    }

    private int scalar(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
