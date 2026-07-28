package com.apms.config;

import com.apms.common.enums.CandidateStatus;
import com.apms.common.enums.NotificationType;
import com.apms.common.enums.RelationshipType;
import com.apms.common.enums.SystemRole;
import com.apms.domain.candidate.CompanyCandidate;
import com.apms.domain.user.Account;
import com.apms.domain.user.UserProfile;
import com.apms.domain.user.repository.sql.AccountRepository;
import com.apms.domain.user.repository.sql.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Set;
import org.springframework.core.annotation.Order;

@Slf4j
@Component
@Profile("dev")
@Order(1)
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    private final AccountRepository accountRepository;
    private final UserProfileRepository userProfileRepository;
    private final PasswordEncoder passwordEncoder;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    private final MongoTemplate mongoTemplate;
    private final Environment environment;

    @Override
    public void run(String... args) {
        String[] activeProfiles = environment.getActiveProfiles();
        boolean isProd = java.util.Arrays.asList(activeProfiles).contains("prod");
        if (isProd) {
            log.error("CRITICAL: DataSeeder refused to run — active profile is 'prod'. This seeder is for development only.");
            return;
        }

        log.warn("Running Development DataSeeder on profiles: {}. Seed passwords (123456) are for dev/demo use ONLY.", String.join(",", activeProfiles));
        
        try {
            log.info("Dropping old CHECK constraints on projects and audit_logs to allow new enum values...");
            jdbcTemplate.execute(
                "DECLARE @sql NVARCHAR(MAX) = N''; " +
                "SELECT @sql += N'ALTER TABLE projects DROP CONSTRAINT ' + name + ';' " +
                "FROM sys.check_constraints " +
                "WHERE parent_object_id = OBJECT_ID('projects'); " +
                "EXEC sp_executesql @sql;"
            );
            jdbcTemplate.execute(
                "DECLARE @sql NVARCHAR(MAX) = N''; " +
                "SELECT @sql += N'ALTER TABLE audit_logs DROP CONSTRAINT ' + name + ';' " +
                "FROM sys.check_constraints " +
                "WHERE parent_object_id = OBJECT_ID('audit_logs'); " +
                "EXEC sp_executesql @sql;"
            );
        } catch (Exception e) {
            log.warn("Failed to drop CHECK constraints: {}", e.getMessage());
        }

        try {
            log.info("Converting projects and project_tasks text columns to NVARCHAR for Vietnamese Unicode support...");
            jdbcTemplate.execute("ALTER TABLE projects ALTER COLUMN project_name NVARCHAR(255) NOT NULL");
            jdbcTemplate.execute("ALTER TABLE project_tasks ALTER COLUMN title NVARCHAR(255) NOT NULL");
        } catch (Exception e) {
            log.warn("Failed to alter columns to NVARCHAR: {}", e.getMessage());
        }

        try {
            log.info("Fixing legacy projects with invalid IN_PROGRESS status...");
            int rows = jdbcTemplate.update("UPDATE projects SET status = 'ACTIVE' WHERE status = 'IN_PROGRESS'");
            log.info("Successfully updated {} legacy projects to status ACTIVE.", rows);
        } catch (Exception e) {
            log.warn("Failed to update legacy project statuses: {}", e.getMessage());
        }

        boolean needsReset = false;
        try {
            Integer countWithQuestionProject = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM projects WHERE project_name LIKE '%?%'", Integer.class
            );
            Integer countWithQuestionTask = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM project_tasks WHERE title LIKE '%?%'", Integer.class
            );
            if ((countWithQuestionProject != null && countWithQuestionProject > 0) ||
                (countWithQuestionTask != null && countWithQuestionTask > 0)) {
                needsReset = true;
            }
        } catch (Exception e) {
            log.warn("Failed to check for corrupted project/task encoding: {}", e.getMessage());
        }

        if (needsReset) {
            log.info("Corrupted encoding ('?') detected in legacy project/task database records. Wiping tables to re-seed fresh...");
            try {
                jdbcTemplate.execute("DELETE FROM project_task_submissions");
                jdbcTemplate.execute("DELETE FROM project_task_drafts");
                jdbcTemplate.execute("DELETE FROM project_tasks");
                jdbcTemplate.execute("DELETE FROM project_members");
                jdbcTemplate.execute("DELETE FROM projects");
                log.info("Database tables wiped successfully.");
            } catch (Exception e) {
                log.warn("Failed to wipe legacy projects/tasks tables: {}", e.getMessage());
            }
        }

        try {
            log.info("Dropping old CHECK constraints on account_roles to allow new enum values...");
            jdbcTemplate.execute(
                "DECLARE @sql NVARCHAR(MAX) = N''; " +
                "SELECT @sql += N'ALTER TABLE account_roles DROP CONSTRAINT ' + name + ';' " +
                "FROM sys.check_constraints " +
                "WHERE parent_object_id = OBJECT_ID('account_roles'); " +
                "EXEC sp_executesql @sql;"
            );
        } catch (Exception e) {
            log.warn("Failed to drop CHECK constraint on account_roles: {}", e.getMessage());
        }

        createUserIfNotFound("admin@apms.com", "admin", "System", "Admin", SystemRole.SYSTEM_ADMIN);
        createUserIfNotFound("owner@apms.com", "owner", "Business", "Owner", SystemRole.BUSINESS_OWNER);
        createUserIfNotFound("director@apms.com", "director", "Business", "Director", SystemRole.BUSINESS_DIRECTOR);
        createUserIfNotFound("manager@apms.com", "manager", "Business", "Manager", SystemRole.BUSINESS_DEVELOPMENT_MANAGER);
        createUserIfNotFound("keymember@apms.com", "keymember", "Key", "Member", SystemRole.KEY_MEMBER);
        createUserIfNotFound("staff@apms.com", "staff", "Research", "Staff", SystemRole.BUSINESS_DEVELOPMENT_STAFF);

        seedTestCandidates();
        seedEvaluationTask();
        seedTestNotifications();

        log.info("Development DataSeeder completed.");
    }

    private void seedTestCandidates() {
        long existingCount = mongoTemplate.count(new Query(), CompanyCandidate.class);
        if (existingCount > 0) {
            log.info("Skipping candidate seed: {} candidates already exist.", existingCount);
            return;
        }

        try {
            Long staffId = jdbcTemplate.queryForObject(
                "SELECT id FROM accounts WHERE email = 'staff@apms.com'", Long.class);
            Long managerId = jdbcTemplate.queryForObject(
                "SELECT id FROM accounts WHERE email = 'manager@apms.com'", Long.class);

            jdbcTemplate.update(
                "INSERT INTO projects (project_name, project_type, target_company_name, target_relationship_type, status, created_by, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, GETUTCDATE(), GETUTCDATE())",
                "Seed Candidate Project", "RESEARCH_NEW_COMPANY", "Seed Corp", "POTENTIAL_PARTNER_OF", "ACTIVE", managerId);
            Long projectId = jdbcTemplate.queryForObject("SELECT SCOPE_IDENTITY()", Long.class);

            jdbcTemplate.update(
                "INSERT INTO project_members (project_id, account_id, member_role, joined_at) VALUES (?, ?, ?, GETUTCDATE())",
                projectId, managerId, "MANAGER");
            jdbcTemplate.update(
                "INSERT INTO project_members (project_id, account_id, member_role, joined_at) VALUES (?, ?, ?, GETUTCDATE())",
                projectId, staffId, "STAFF");

            jdbcTemplate.update(
                "INSERT INTO import_jobs (project_id, input_type, source_type, file_name, status, uploaded_by, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, GETUTCDATE())",
                projectId, "FILE_UPLOAD", "PDF", "seed-test.pdf", "COMPLETED", staffId);
            Long importJobId = jdbcTemplate.queryForObject("SELECT SCOPE_IDENTITY()", Long.class);

            CompanyCandidate candidate1 = CompanyCandidate.builder()
                    .projectId(String.valueOf(projectId))
                    .importJobId(String.valueOf(importJobId))
                    .candidateOrder(1)
                    .revisionNumber(1)
                    .status(CandidateStatus.PENDING_REVIEW)
                    .suggestedRelationshipType(RelationshipType.POTENTIAL_PARTNER_OF)
                    .relationshipConfidenceScore(0.85)
                    .identity(CompanyCandidate.Identity.builder()
                            .legalName("Seed Corp International")
                            .taxCode("SEED-001")
                            .build())
                    .business(CompanyCandidate.Business.builder()
                            .industries(List.of("Technology", "Software"))
                            .businessModel("B2B SaaS")
                            .build())
                    .contact(CompanyCandidate.Contact.builder()
                            .website("https://seedcorp.example.com")
                            .emails(List.of("contact@seedcorp.example.com"))
                            .build())
                    .lifecycle(CompanyCandidate.Lifecycle.builder()
                            .status(CandidateStatus.PENDING_REVIEW)
                            .build())
                    .metadata(CompanyCandidate.Metadata.builder()
                            .createdBy(String.valueOf(staffId))
                            .build())
                    .build();
            mongoTemplate.save(candidate1, "company_candidates");

            CompanyCandidate candidate2 = CompanyCandidate.builder()
                    .projectId(String.valueOf(projectId))
                    .importJobId(String.valueOf(importJobId))
                    .candidateOrder(2)
                    .revisionNumber(1)
                    .status(CandidateStatus.DRAFT)
                    .suggestedRelationshipType(RelationshipType.COMPETITOR_OF)
                    .relationshipConfidenceScore(0.72)
                    .identity(CompanyCandidate.Identity.builder()
                            .legalName("Draft Startup Ltd")
                            .taxCode("DRAFT-002")
                            .build())
                    .business(CompanyCandidate.Business.builder()
                            .industries(List.of("Finance", "Fintech"))
                            .businessModel("B2C")
                            .build())
                    .lifecycle(CompanyCandidate.Lifecycle.builder()
                            .status(CandidateStatus.DRAFT)
                            .build())
                    .metadata(CompanyCandidate.Metadata.builder()
                            .createdBy(String.valueOf(staffId))
                            .build())
                    .build();
            mongoTemplate.save(candidate2, "company_candidates");

            log.info("Seeded 2 test candidates for project id={}", projectId);
        } catch (Exception e) {
            log.warn("Failed to seed test candidates: {}", e.getMessage());
        }
    }

    private void seedEvaluationTask() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM project_tasks WHERE task_type = 'ROLE_EVALUATION'", Integer.class);
            if (count != null && count > 0) {
                log.info("Skipping evaluation task seed: {} ROLE_EVALUATION tasks already exist.", count);
                return;
            }

            Long staffId = jdbcTemplate.queryForObject(
                "SELECT id FROM accounts WHERE email = 'staff@apms.com'", Long.class);
            Long managerId = jdbcTemplate.queryForObject(
                "SELECT id FROM accounts WHERE email = 'manager@apms.com'", Long.class);

            Long projectId = jdbcTemplate.queryForObject(
                "SELECT TOP 1 id FROM projects WHERE status = 'ACTIVE' ORDER BY id DESC", Long.class);
            if (projectId == null) {
                log.warn("No active project found, cannot seed evaluation task.");
                return;
            }

            jdbcTemplate.update(
                "INSERT INTO project_tasks (project_id, assigned_to_account_id, title, description, status, task_type, priority, created_by_account_id, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, GETUTCDATE(), GETUTCDATE())",
                projectId, staffId, "Seed Role Evaluation Task", "Task for evaluation testing", "TODO", "ROLE_EVALUATION", "MEDIUM", managerId);
            log.info("Seeded ROLE_EVALUATION task for project id={}", projectId);
        } catch (Exception e) {
            log.warn("Failed to seed evaluation task: {}", e.getMessage());
        }
    }

    private void seedTestNotifications() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE is_deleted = 0", Integer.class);
            if (count != null && count > 0) {
                log.info("Skipping notification seed: {} notifications already exist.", count);
                return;
            }

            Long staffId = jdbcTemplate.queryForObject(
                "SELECT id FROM accounts WHERE email = 'staff@apms.com'", Long.class);
            Long managerId = jdbcTemplate.queryForObject(
                "SELECT id FROM accounts WHERE email = 'manager@apms.com'", Long.class);

            jdbcTemplate.update(
                "INSERT INTO notifications (recipient_account_id, sender_account_id, title, message, type, is_read, is_deleted, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, 0, 0, GETUTCDATE(), GETUTCDATE())",
                staffId, managerId, "Task Assigned", "You have a new data collection task assigned.", "TASK");

            jdbcTemplate.update(
                "INSERT INTO notifications (recipient_account_id, sender_account_id, title, message, type, is_read, is_deleted, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, 0, 0, GETUTCDATE(), GETUTCDATE())",
                staffId, managerId, "System Maintenance", "Scheduled maintenance window tonight.", "SYSTEM");

            log.info("Seeded 2 test notifications for staff user.");
        } catch (Exception e) {
            log.warn("Failed to seed test notifications: {}", e.getMessage());
        }
    }

    private void createUserIfNotFound(String email, String username, String firstName, String lastName, SystemRole role) {
        if (accountRepository.existsByEmail(email)) {
            log.info("Demo account already exists: {}. Updating password and role.", email);
            Account account = accountRepository.findByEmail(email).get();
            account.setPasswordHash(passwordEncoder.encode("123456"));
            account.setRoles(Set.of(role));
            accountRepository.save(account);
            return;
        }

        try {
            Account account = Account.builder()
                    .email(email)
                    .username(username)
                    .passwordHash(passwordEncoder.encode("123456"))
                    .isActive(true)
                    .roles(Set.of(role))
                    .build();
            account = accountRepository.save(account);

            UserProfile profile = UserProfile.builder()
                    .account(account)
                    .firstName(firstName)
                    .lastName(lastName)
                    .build();
            userProfileRepository.save(profile);

            log.info("Created demo account & profile: {} with role: {}", email, role);
        } catch (DataIntegrityViolationException e) {
            log.warn("Skipped demo account {} because role {} is not allowed by the current schema.", email, role);
        }
    }
}
