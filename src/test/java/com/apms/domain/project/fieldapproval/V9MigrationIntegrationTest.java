//package com.apms.domain.project.fieldapproval;
//
//import com.apms.ApmsIntegrationTestBase;
//import org.junit.jupiter.api.Test;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.jdbc.core.JdbcTemplate;
//import org.springframework.util.FileCopyUtils;
//
//import java.io.File;
//import java.io.FileReader;
//import java.util.List;
//import java.util.Map;
//
//import static org.junit.jupiter.api.Assertions.*;
//
//public class V9MigrationIntegrationTest extends ApmsIntegrationTestBase {
//
//    @Autowired
//    private JdbcTemplate jdbcTemplate;
//
//    @Test
//    void testV9MigrationIsSafeAndIdempotent() throws Exception {
//        // Read the SQL script
//        File v9File = new File("docs/sql-migrations/V9__Add_Submission_Revision_Number.sql");
//        assertTrue(v9File.exists(), "V9 migration file must exist");
//        String sqlScript = FileCopyUtils.copyToString(new FileReader(v9File));
//
//        // Split by GO and run
//        runSqlScript(sqlScript);
//
//        // Verify column submitted_revision_number exists
//        List<Map<String, Object>> columns = jdbcTemplate.queryForList(
//                "SELECT column_name FROM information_schema.columns " +
//                "WHERE table_name = 'project_task_submissions' AND column_name = 'submitted_revision_number'");
//        assertEquals(1, columns.size(), "submitted_revision_number column should exist");
//
//        // Verify unique index exists
//        List<Map<String, Object>> indexes = jdbcTemplate.queryForList(
//                "SELECT name FROM sys.indexes " +
//                "WHERE name = 'UQ_ProjectTaskSubmission_TargetEntityRev' AND object_id = OBJECT_ID('project_task_submissions')");
//        assertEquals(1, indexes.size(), "UQ_ProjectTaskSubmission_TargetEntityRev should exist");
//
//        // Run it a second time to verify idempotency
//        runSqlScript(sqlScript);
//
//        // It shouldn't crash or duplicate columns
//        columns = jdbcTemplate.queryForList(
//                "SELECT column_name FROM information_schema.columns " +
//                "WHERE table_name = 'project_task_submissions' AND column_name = 'submitted_revision_number'");
//        assertEquals(1, columns.size(), "submitted_revision_number column should still exist exactly once");
//
//        // Verify no PostgreSQL syntax
//        assertFalse(sqlScript.contains("ON CONFLICT DO NOTHING"));
//        assertFalse(sqlScript.contains("CREATE UNIQUE INDEX") && sqlScript.contains("COALESCE"));
//
//        // Setup some dummy foreign keys to satisfy inserts
//        jdbcTemplate.execute("DELETE FROM project_task_submissions WHERE id IN (998, 999, 1000, 1001, 1002)");
//        jdbcTemplate.execute("DELETE FROM project_tasks WHERE id = 999");
//        jdbcTemplate.execute("DELETE FROM projects WHERE id = 999");
//        jdbcTemplate.execute("DELETE FROM accounts WHERE id = 999 OR email = 'test999@example.com'");
//
//        jdbcTemplate.execute("SET IDENTITY_INSERT accounts ON; INSERT INTO accounts (id, email, is_active, password_hash) VALUES (999, 'test999@example.com', 1, 'hash'); SET IDENTITY_INSERT accounts OFF;");
//        jdbcTemplate.execute("SET IDENTITY_INSERT projects ON; INSERT INTO projects (id, created_by, project_name, status, target_company_name, project_type) VALUES (999, 999, 'Test', 'ACTIVE', 'Company', 'RESEARCH_NEW_COMPANY'); SET IDENTITY_INSERT projects OFF;");
//        jdbcTemplate.execute("SET IDENTITY_INSERT project_tasks ON; INSERT INTO project_tasks (id, project_id, task_type, status, created_by_account_id, title) VALUES (999, 999, 'COMPANY_DATA_PREPARATION', 'TODO', 999, 'Test Task'); SET IDENTITY_INSERT project_tasks OFF;");
//
//        // Test null legacy revisions are allowed
//        jdbcTemplate.execute("SET IDENTITY_INSERT project_task_submissions ON; INSERT INTO project_task_submissions (id, project_task_id, project_id, target_entity_type, target_entity_id, status, submitted_by_account_id, submission_type, submitted_at) VALUES (998, 999, 999, 'CompanyCandidate', 'cand1', 'DRAFT', 999, 'COMPANY_CANDIDATE', '2025-01-01 10:00:00'); SET IDENTITY_INSERT project_task_submissions OFF;");
//        jdbcTemplate.execute("SET IDENTITY_INSERT project_task_submissions ON; INSERT INTO project_task_submissions (id, project_task_id, project_id, target_entity_type, target_entity_id, status, submitted_by_account_id, submission_type, submitted_at) VALUES (999, 999, 999, 'CompanyCandidate', 'cand1', 'IN_REVIEW', 999, 'COMPANY_CANDIDATE', '2025-01-01 10:00:00'); SET IDENTITY_INSERT project_task_submissions OFF;");
//        // We inserted two nulls for cand1 - should succeed because filtered index ignores nulls
//
//        // Test duplicate non-null revision is rejected
//        jdbcTemplate.execute("SET IDENTITY_INSERT project_task_submissions ON; INSERT INTO project_task_submissions (id, project_task_id, project_id, target_entity_type, target_entity_id, status, submitted_revision_number, submitted_by_account_id, submission_type, submitted_at) VALUES (1000, 999, 999, 'CompanyCandidate', 'cand2', 'SUBMITTED', 1, 999, 'COMPANY_CANDIDATE', '2025-01-01 10:00:00'); SET IDENTITY_INSERT project_task_submissions OFF;");
//        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> {
//            jdbcTemplate.execute("SET IDENTITY_INSERT project_task_submissions ON; INSERT INTO project_task_submissions (id, project_task_id, project_id, target_entity_type, target_entity_id, status, submitted_revision_number, submitted_by_account_id, submission_type, submitted_at) VALUES (1001, 999, 999, 'CompanyCandidate', 'cand2', 'IN_REVIEW', 1, 999, 'COMPANY_CANDIDATE', '2025-01-01 10:00:00'); SET IDENTITY_INSERT project_task_submissions OFF;");
//        });
//
//        // Test REVISION_REQUESTED persists
//        jdbcTemplate.execute("SET IDENTITY_INSERT project_task_submissions ON; INSERT INTO project_task_submissions (id, project_task_id, project_id, target_entity_type, target_entity_id, status, submitted_revision_number, submitted_by_account_id, submission_type, submitted_at) VALUES (1002, 999, 999, 'CompanyCandidate', 'cand3', 'REVISION_REQUESTED', 1, 999, 'COMPANY_CANDIDATE', '2025-01-01 10:00:00'); SET IDENTITY_INSERT project_task_submissions OFF;");
//        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM project_task_submissions WHERE status = 'REVISION_REQUESTED'", Integer.class);
//        assertTrue(count > 0);
//    }
//
//    private void runSqlScript(String sqlScript) {
//        String[] parts = sqlScript.split("(?i)\\bGO\\b");
//        for (String part : parts) {
//            String sql = part.trim();
//            if (!sql.isEmpty()) {
//                jdbcTemplate.execute(sql);
//            }
//        }
//    }
//}
