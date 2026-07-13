package com.fleet.vts.scheduler.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.function.LongSupplier;

/** Wraps each job so every run is recorded in {@code job_execution}. */
@Component
public class JobRunner {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);

    private final JdbcTemplate jdbc;

    public JobRunner(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void run(String jobName, LongSupplier work) {
        Long id = jdbc.queryForObject(
                "INSERT INTO job_execution (job_name, status, node) VALUES (?, 'RUNNING', ?) RETURNING id",
                Long.class, jobName, node());
        try {
            long rows = work.getAsLong();
            jdbc.update("UPDATE job_execution SET status = 'SUCCESS', finished_at = now(), rows_affected = ? WHERE id = ?",
                    rows, id);
            log.info("Job {} finished, rows={}", jobName, rows);
        } catch (Exception e) {
            jdbc.update("UPDATE job_execution SET status = 'FAILED', finished_at = now(), detail = ?::jsonb WHERE id = ?",
                    "{\"error\":\"" + e.getMessage() + "\"}", id);
            log.error("Job {} failed: {}", jobName, e.getMessage());
        }
    }

    private String node() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
