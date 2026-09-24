package com.apms.common.config;

import com.apms.common.enums.SubmissionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * One-time migration: updates the CHECK constraint on project_task_submissions.status
 * to include all values from the SubmissionStatus enum (including WITHDRAWN). Safe to re-run (idempotent).
 */
@Slf4j
@Component
@Order(4)
@RequiredArgsConstructor
public class SubmissionStatusConstraintMigration implements CommandLineRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(String... args) {
        try {
            // Find and drop any existing CHECK constraints on status column in project_task_submissions
            var constraints = jdbcTemplate.queryForList(
                "SELECT cc.CONSTRAINT_NAME " +
                "FROM INFORMATION_SCHEMA.CONSTRAINT_COLUMN_USAGE ccu " +
                "JOIN INFORMATION_SCHEMA.CHECK_CONSTRAINTS cc ON cc.CONSTRAINT_NAME = ccu.CONSTRAINT_NAME " +
                "WHERE ccu.TABLE_NAME = 'project_task_submissions' AND ccu.COLUMN_NAME = 'status'"
            );

            for (var row : constraints) {
                String constraintName = (String) row.get("CONSTRAINT_NAME");
                log.info("Dropping CHECK constraint on project_task_submissions.status: {}", constraintName);
                jdbcTemplate.execute("ALTER TABLE project_task_submissions DROP CONSTRAINT [" + constraintName + "]");
            }

            // Generate the IN clause dynamically from the Enum
            String inClause = Arrays.stream(SubmissionStatus.values())
                    .map(status -> "'" + status.name() + "'")
                    .collect(Collectors.joining(", "));

            // Re-create constraint
            String newConstraint =
                "ALTER TABLE project_task_submissions ADD CONSTRAINT CK_project_task_submissions_status " +
                "CHECK (status IN (" + inClause + "))";

            jdbcTemplate.execute(newConstraint);
            log.info("Successfully re-created CHECK constraint CK_project_task_submissions_status with all Enum values (including WITHDRAWN).");

        } catch (Exception e) {
            log.warn("SubmissionStatus constraint migration skipped or failed: {}", e.getMessage());
        }
    }
}