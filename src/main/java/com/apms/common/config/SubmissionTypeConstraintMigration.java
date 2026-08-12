package com.apms.common.config;

import com.apms.common.enums.SubmissionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * One-time migration: updates the CHECK constraint on project_task_submissions.submission_type
 * to include all values from the SubmissionType enum. Safe to re-run (idempotent).
 */
@Slf4j
@Component
@Order(3)
@RequiredArgsConstructor
public class SubmissionTypeConstraintMigration implements CommandLineRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(String... args) {
        try {
            // Find and drop any existing CHECK constraints on submission_type column
            var constraints = jdbcTemplate.queryForList(
                "SELECT cc.CONSTRAINT_NAME " +
                "FROM INFORMATION_SCHEMA.CONSTRAINT_COLUMN_USAGE ccu " +
                "JOIN INFORMATION_SCHEMA.CHECK_CONSTRAINTS cc ON cc.CONSTRAINT_NAME = ccu.CONSTRAINT_NAME " +
                "WHERE ccu.TABLE_NAME = 'project_task_submissions' AND ccu.COLUMN_NAME = 'submission_type'"
            );

            for (var row : constraints) {
                String constraintName = (String) row.get("CONSTRAINT_NAME");
                log.info("Dropping CHECK constraint: {}", constraintName);
                jdbcTemplate.execute("ALTER TABLE project_task_submissions DROP CONSTRAINT [" + constraintName + "]");
            }

            // Generate the IN clause dynamically from the Enum
            String inClause = Arrays.stream(SubmissionType.values())
                    .map(type -> "'" + type.name() + "'")
                    .collect(Collectors.joining(", "));

            // Re-create constraint
            String newConstraint =
                "ALTER TABLE project_task_submissions ADD CONSTRAINT CK_project_task_submissions_submission_type " +
                "CHECK (submission_type IN (" + inClause + "))";
                
            jdbcTemplate.execute(newConstraint);
            log.info("Successfully re-created CHECK constraint CK_project_task_submissions_submission_type with all Enum values.");

        } catch (Exception e) {
            log.warn("SubmissionType constraint migration skipped or failed: {}", e.getMessage());
        }
    }
}
