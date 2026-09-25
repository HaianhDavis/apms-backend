package com.apms.common.config;

import com.apms.common.enums.AuditAction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * One-time migration: updates the CHECK constraint on audit_logs.action
 * to include all values from the AuditAction enum. Safe to re-run (idempotent).
 */
@Slf4j
@Component
@Order(2)
@RequiredArgsConstructor
public class AuditActionConstraintMigration implements CommandLineRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(String... args) {
        try {
            // Find and drop any existing CHECK constraints on action column
            var constraints = jdbcTemplate.queryForList(
                "SELECT cc.CONSTRAINT_NAME " +
                "FROM INFORMATION_SCHEMA.CONSTRAINT_COLUMN_USAGE ccu " +
                "JOIN INFORMATION_SCHEMA.CHECK_CONSTRAINTS cc ON cc.CONSTRAINT_NAME = ccu.CONSTRAINT_NAME " +
                "WHERE ccu.TABLE_NAME = 'audit_logs' AND ccu.COLUMN_NAME = 'action'"
            );

            for (var row : constraints) {
                String constraintName = (String) row.get("CONSTRAINT_NAME");
                log.info("Dropping CHECK constraint: {}", constraintName);
                jdbcTemplate.execute("ALTER TABLE audit_logs DROP CONSTRAINT [" + constraintName + "]");
            }

            // Generate the IN clause dynamically from the Enum
            String inClause = Arrays.stream(AuditAction.values())
                    .map(action -> "'" + action.name() + "'")
                    .collect(Collectors.joining(", "));

            // Re-create constraint
            String newConstraint =
                "ALTER TABLE audit_logs ADD CONSTRAINT CK_audit_logs_action " +
                "CHECK (action IN (" + inClause + "))";
                
            jdbcTemplate.execute(newConstraint);
            log.info("Successfully re-created CHECK constraint CK_audit_logs_action with all Enum values.");

        } catch (Exception e) {
            log.warn("AuditAction constraint migration skipped or failed: {}", e.getMessage());
        }
    }
}
