package com.apms.common.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * One-time migration: updates the CHECK constraint on project_tasks.task_type
 * to include COMPANY_NEWS_RESEARCH. Safe to re-run (idempotent).
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class TaskTypeConstraintMigration implements CommandLineRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(String... args) {
        try {
            // Find and drop any existing CHECK constraints on task_type column
            var constraints = jdbcTemplate.queryForList(
                "SELECT cc.CONSTRAINT_NAME " +
                "FROM INFORMATION_SCHEMA.CONSTRAINT_COLUMN_USAGE ccu " +
                "JOIN INFORMATION_SCHEMA.CHECK_CONSTRAINTS cc ON cc.CONSTRAINT_NAME = ccu.CONSTRAINT_NAME " +
                "WHERE ccu.TABLE_NAME = 'project_tasks' AND ccu.COLUMN_NAME = 'task_type'"
            );

            for (var row : constraints) {
                String constraintName = (String) row.get("CONSTRAINT_NAME");
                log.info("Dropping CHECK constraint: {}", constraintName);
                jdbcTemplate.execute("ALTER TABLE project_tasks DROP CONSTRAINT [" + constraintName + "]");
            }

            // Re-create with all task types including FINANCIAL_RESEARCH
            String newConstraint =
                "ALTER TABLE project_tasks ADD CONSTRAINT CK_project_tasks_task_type " +
                "CHECK (task_type IN (" +
                "'GENERAL_TASK'," +
                "'DOCUMENT_COLLECTION'," +
                "'COMPANY_DATA_PREPARATION'," +
                "'ROLE_EVALUATION'," +
                "'COMPANY_MEMBER_RESEARCH'," +
                "'COMPANY_NEWS_RESEARCH'," +
                "'FINANCIAL_RESEARCH'," +
                "'PARTNER_CONTRACT_COLLECTION'" +
                "))";
            jdbcTemplate.execute(newConstraint);
            log.info("Successfully re-created CHECK constraint CK_project_tasks_task_type with FINANCIAL_RESEARCH");

            // Migrate existing tasks
            int updated = jdbcTemplate.update(
                "UPDATE project_tasks SET task_type = 'FINANCIAL_RESEARCH' " +
                "WHERE task_type = 'COMPANY_DATA_PREPARATION' AND title = 'Research Financial Information'"
            );
            if (updated > 0) {
                log.info("Migrated {} existing financial research tasks to FINANCIAL_RESEARCH type", updated);
            }

        } catch (Exception e) {
            log.warn("TaskType constraint migration skipped or failed: {}", e.getMessage());
        }
    }
}
