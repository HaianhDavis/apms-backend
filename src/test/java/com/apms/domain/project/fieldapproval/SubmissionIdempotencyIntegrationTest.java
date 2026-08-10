package com.apms.domain.project.fieldapproval;

import com.apms.ApmsIntegrationTestBase;
import com.apms.common.enums.SubmissionType;
import com.apms.common.enums.TaskStatus;
import com.apms.common.enums.TaskType;
import com.apms.domain.candidate.CompanyCandidate;
import com.apms.domain.candidate.repository.mongo.CompanyCandidateRepository;
import com.apms.domain.project.Project;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.ProjectTaskSubmission;
import com.apms.domain.project.dto.CreateProjectTaskSubmissionRequest;
import com.apms.domain.project.dto.ProjectTaskSubmissionResponse;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.domain.project.repository.sql.ProjectTaskSubmissionRepository;
import com.apms.domain.project.service.ProjectTaskSubmissionService;
import com.apms.domain.user.Account;
import com.apms.domain.user.repository.sql.AccountRepository;
import com.apms.security.UserDetailsImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.jdbc.JdbcTestUtils;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class SubmissionIdempotencyIntegrationTest extends ApmsIntegrationTestBase {

    @Autowired
    private ProjectTaskSubmissionService service;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ProjectTaskRepository taskRepository;

    @Autowired
    private ProjectTaskSubmissionRepository submissionRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CompanyCandidateRepository candidateRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Account account;
    private Project project;
    private ProjectTask task;
    private CompanyCandidate candidate;

    @BeforeEach
    void setUp() {
        JdbcTestUtils.deleteFromTables(jdbcTemplate, "audit_logs", "project_task_submissions", "project_tasks", "score_snapshots", "project_members", "projects", "account_roles", "users", "accounts");
        candidateRepository.deleteAll();

        account = new Account();
        account.setEmail("idemp-testuser@example.com");
        account.setPasswordHash("hash");
        account = accountRepository.save(account);

        project = new Project();
        project.setProjectName("Idempotency Project");
        project.setProjectType(com.apms.common.enums.ProjectType.RESEARCH_NEW_COMPANY);
        project.setTargetCompanyName("Test Company");
        project.setCreatedByAccount(account);
        project.setStatus(com.apms.common.enums.ProjectStatus.ACTIVE);
        project = projectRepository.save(project);

        task = new ProjectTask();
        task.setProject(project);
        task.setAssignedToAccount(account);
        task.setCreatedByAccount(account);
        task.setStatus(TaskStatus.TODO);
        task.setTaskType(TaskType.COMPANY_DATA_PREPARATION);
        task.setTitle("Idemp Task");
        task = taskRepository.save(task);

        candidate = new CompanyCandidate();
        candidate.setProjectId(String.valueOf(project.getId()));
        candidate.setRevisionNumber(1);
        candidate.setStatus(com.apms.common.enums.CandidateStatus.DRAFT);
        candidate = candidateRepository.save(candidate);

        UserDetailsImpl user = new UserDetailsImpl(
                account.getId(), "idemp", "pass",
                List.of(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN")), true
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities())
        );
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_fail_submission_insert");
        SecurityContextHolder.clearContext();
    }

    @Test
    void testFirstSubmissionPartialFailure() {
        CreateProjectTaskSubmissionRequest request = new CreateProjectTaskSubmissionRequest();
        request.setSubmissionType(SubmissionType.COMPANY_CANDIDATE);
        request.setTargetEntityType("CompanyCandidate");
        request.setTargetEntityId(candidate.getId());
        request.setNote("First submit attempt");

        // Inject SQL failure via trigger
        jdbcTemplate.execute("CREATE TRIGGER trg_fail_submission_insert ON project_task_submissions AFTER INSERT AS BEGIN RAISERROR('Simulated insert failure', 16, 1); ROLLBACK TRANSACTION; END;");

        Exception ex = assertThrows(Exception.class, () -> service.submitTask(project.getId(), task.getId(), request));

        // Mongo submission update should have succeeded (it was in a separate Mongo transaction or auto-commit)
        CompanyCandidate updatedCandidate = candidateRepository.findById(candidate.getId()).orElseThrow();
        assertEquals(com.apms.common.enums.CandidateStatus.PENDING_REVIEW, updatedCandidate.getStatus());
        assertEquals(1, updatedCandidate.getRevisionNumber()); // The working revision was copied to revisionNumber

        // SQL should have 0 submissions
        assertEquals(0, submissionRepository.count());

        // Now remove the trigger and retry
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_fail_submission_insert");

        ProjectTaskSubmissionResponse retryResponse = service.submitTask(project.getId(), task.getId(), request);
        assertNotNull(retryResponse);

        // Verify it didn't increment revision again
        CompanyCandidate finalCandidate = candidateRepository.findById(candidate.getId()).orElseThrow();
        assertEquals(1, finalCandidate.getRevisionNumber());

        // Verify exactly one submission exists in SQL with revision 1
        List<ProjectTaskSubmission> subs = submissionRepository.findAll();
        assertEquals(1, subs.size());
        assertEquals(1, subs.get(0).getSubmittedRevisionNumber());
    }

    @Test
    void testResubmissionPartialFailure() {
        // 1. Successful first submission
        CreateProjectTaskSubmissionRequest request = new CreateProjectTaskSubmissionRequest();
        request.setSubmissionType(SubmissionType.COMPANY_CANDIDATE);
        request.setTargetEntityType("CompanyCandidate");
        request.setTargetEntityId(candidate.getId());
        request.setNote("First submit");

        service.submitTask(project.getId(), task.getId(), request);

        // Change candidate to DRAFT and bump working revision to simulate Manager requesting revision
        CompanyCandidate firstSubmitted = candidateRepository.findById(candidate.getId()).orElseThrow();
        firstSubmitted.setStatus(com.apms.common.enums.CandidateStatus.DRAFT);
        candidateRepository.save(firstSubmitted);

        // Also mark the task and submission as REVISION_REQUESTED so we can submit again
        task.setStatus(com.apms.common.enums.TaskStatus.IN_PROGRESS);
        taskRepository.save(task);

        ProjectTaskSubmission existingSub = submissionRepository.findAll().get(0);
        existingSub.setStatus(com.apms.common.enums.SubmissionStatus.REVISION_REQUESTED);
        submissionRepository.save(existingSub);

        // Delete previous submission so we can do an INSERT again without Unique Constraint on Task (or just let the test continue)
        // Wait, the unique index is on (target_entity_id, submitted_revision_number).
        
        // 2. Inject SQL failure for Resubmission
        jdbcTemplate.execute("CREATE TRIGGER trg_fail_submission_insert ON project_task_submissions AFTER INSERT AS BEGIN RAISERROR('Simulated insert failure', 16, 1); ROLLBACK TRANSACTION; END;");

        Exception ex = assertThrows(Exception.class, () -> service.submitTask(project.getId(), task.getId(), request));

        // Mongo draft should be at revision N+1 (which is 2)
        CompanyCandidate afterFail = candidateRepository.findById(candidate.getId()).orElseThrow();
        assertEquals(2, afterFail.getRevisionNumber());
        assertEquals(com.apms.common.enums.CandidateStatus.PENDING_REVIEW, afterFail.getStatus());

        // SQL shouldn't have revision 2
        List<ProjectTaskSubmission> subs = submissionRepository.findAll();
        assertEquals(1, subs.size());
        assertEquals(1, subs.get(0).getSubmittedRevisionNumber());

        // 3. Drop trigger and Retry
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_fail_submission_insert");

        ProjectTaskSubmissionResponse retryResponse = service.submitTask(project.getId(), task.getId(), request);
        assertNotNull(retryResponse);

        // Verify it kept N+1 and didn't create N+2
        CompanyCandidate finalCandidate = candidateRepository.findById(candidate.getId()).orElseThrow();
        assertEquals(2, finalCandidate.getRevisionNumber());
        
        // Exactly two submissions exist (rev 1 and rev 2)
        subs = submissionRepository.findAll();
        assertEquals(2, subs.size());
        assertTrue(subs.stream().anyMatch(s -> s.getSubmittedRevisionNumber() == 1));
        assertTrue(subs.stream().anyMatch(s -> s.getSubmittedRevisionNumber() == 2));
    }

    @Test
    void testConcurrentDuplicateSubmission() throws InterruptedException {
        CreateProjectTaskSubmissionRequest request = new CreateProjectTaskSubmissionRequest();
        request.setSubmissionType(SubmissionType.COMPANY_CANDIDATE);
        request.setTargetEntityType("CompanyCandidate");
        request.setTargetEntityId(candidate.getId());
        request.setNote("Concurrent submit attempt");

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicReference<Throwable> err = new AtomicReference<>();

        Runnable taskRun = () -> {
            try {
                // Must set auth in each thread
                UserDetailsImpl user = new UserDetailsImpl(
                        account.getId(), "idemp", "pass",
                        List.of(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN")), true
                );
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities())
                );

                latch.await();
                service.submitTask(project.getId(), task.getId(), request);
            } catch (Exception e) {
                // Ignore if it's the expected idempotent return. But in our case, the service returns successfully for duplicates
                // by catching or handling the idempotency check! Wait, the service idempotency check:
                // "if existingSub.isPresent() ... return toResponse(existingSub.get())"
                // Or if it throws DataIntegrityViolation, it could be exposed unless we handle it.
                // We want NO raw DataIntegrityViolationException.
                if (!(e instanceof com.apms.common.exception.BusinessValidationException 
                      || e instanceof org.springframework.dao.DataIntegrityViolationException
                      || e instanceof org.springframework.dao.OptimisticLockingFailureException)) {
                   err.compareAndSet(null, e);
                } else if (e instanceof org.springframework.dao.DataIntegrityViolationException) {
                    err.compareAndSet(null, new IllegalStateException("Exposed raw DB exception: " + e.getMessage(), e));
                }
            } finally {
                done.countDown();
                SecurityContextHolder.clearContext();
            }
        };

        for (int i = 0; i < threadCount; i++) {
            executor.submit(taskRun);
        }

        latch.countDown(); // start all
        done.await(10, TimeUnit.SECONDS);

        if (err.get() != null) {
            fail("Thread failed: ", err.get());
        }

        // database contains exactly one row
        List<ProjectTaskSubmission> subs = submissionRepository.findAll();
        assertEquals(1, subs.size(), "Only one submission should be created despite concurrent calls");
    }
}
