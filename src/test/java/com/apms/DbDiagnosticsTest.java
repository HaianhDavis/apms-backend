package com.apms;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.Map;

@SpringBootTest
public class DbDiagnosticsTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    public void runDiagnostics() {
        System.out.println("=== START DB DIAGNOSTICS ===");
        try {
            // Check what tables exist
            List<Map<String, Object>> tables = jdbcTemplate.queryForList(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_TYPE = 'BASE TABLE'"
            );
            System.out.println("Existing tables: " + tables);

            // Check contents of projects table
            List<Map<String, Object>> projects = jdbcTemplate.queryForList(
                "SELECT id, project_name, status FROM projects"
            );
            System.out.println("Projects in DB BEFORE update: " + projects);

            // Execute SQL update
            int updatedRows = jdbcTemplate.update(
                "UPDATE projects SET status = 'ACTIVE' WHERE status = 'IN_PROGRESS'"
            );
            System.out.println("Updated projects count: " + updatedRows);

            // Check contents after update
            List<Map<String, Object>> projectsAfter = jdbcTemplate.queryForList(
                "SELECT id, project_name, status FROM projects"
            );
            System.out.println("Projects in DB AFTER update: " + projectsAfter);

        } catch (Exception e) {
            System.err.println("Database operation failed!");
            e.printStackTrace();
        }
        System.out.println("=== END DB DIAGNOSTICS ===");
    }
}
