package com.apms.domain.project.service;

import com.apms.common.enums.SystemRole;
import com.apms.common.enums.TaskType;
import com.apms.domain.project.Project;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.ProjectTaskSubmission;
import com.apms.domain.project.dto.FieldReviewRequest;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.project.repository.sql.ProjectTaskSubmissionRepository;
import com.apms.security.UserDetailsImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class FieldReviewServiceAuthorizationTest {

    @Mock
    private ProjectTaskSubmissionRepository submissionRepository;

    @Mock
    private ProjectRepository projectRepository;

    // We only need to mock these to avoid NPE if authorization succeeds and tries to proceed
    @Mock
    private com.apms.domain.candidate.service.CandidateService candidateService;

    @InjectMocks
    private ProjectTaskSubmissionService service;

    private Project project;
    private ProjectTask task;
    private ProjectTaskSubmission submission;

    @BeforeEach
    void setUp() {
        project = new Project();
        project.setId(10L);

        task = new ProjectTask();
        task.setId(20L);
        task.setProject(project);
        task.setTaskType(TaskType.COMPANY_DATA_PREPARATION);

        submission = new ProjectTaskSubmission();
        submission.setId(30L);
        submission.setProject(project);
        submission.setProjectTask(task);
        submission.setTargetEntityType("CompanyCandidate");
        submission.setTargetEntityId("cand-1");
        submission.setStatus(com.apms.common.enums.SubmissionStatus.IN_REVIEW);
        
        com.apms.domain.user.Account submitter = new com.apms.domain.user.Account();
        submitter.setId(100L);
        submission.setSubmittedByAccount(submitter);
        
        org.springframework.test.util.ReflectionTestUtils.setField(service, "candidateService", candidateService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void mockUser(Long userId, SystemRole role) {
        UserDetailsImpl user = new UserDetailsImpl(
                userId, "user" + userId, "pass",
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name())), true
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities())
        );
    }

    @Test
    void testManagerInScopeSucceeds() {
        mockUser(1L, SystemRole.BUSINESS_DEVELOPMENT_MANAGER);
        when(submissionRepository.findById(30L)).thenReturn(Optional.of(submission));
        when(projectRepository.existsByIdAndMembersAccountId(10L, 1L)).thenReturn(true);

        FieldReviewRequest req = new FieldReviewRequest();
        req.setExpectedRevisionNumber(1);

        // This will try to call candidateService.reviewFields (which is mocked and does nothing)
        // because validation passes.
        service.reviewFields(10L, 20L, 30L, req);

        verify(candidateService).reviewFields(eq("cand-1"), eq(req), eq(1L));
    }

    @Test
    void testManagerOutsideScopeDenied() {
        mockUser(1L, SystemRole.BUSINESS_DEVELOPMENT_MANAGER);
        when(submissionRepository.findById(30L)).thenReturn(Optional.of(submission));
        // Manager not in project
        when(projectRepository.existsByIdAndMembersAccountId(10L, 1L)).thenReturn(false);

        FieldReviewRequest req = new FieldReviewRequest();

        assertThrows(AccessDeniedException.class, () ->
                service.reviewFields(10L, 20L, 30L, req)
        );
    }

    @Test
    void testTaskFromAnotherProjectDenied() {
        mockUser(1L, SystemRole.BUSINESS_DEVELOPMENT_MANAGER);
        when(submissionRepository.findById(30L)).thenReturn(Optional.of(submission));
        
        FieldReviewRequest req = new FieldReviewRequest();
        // Request uses projectId 99L, but submission belongs to projectId 10L
        assertThrows(IllegalArgumentException.class, () ->
                service.reviewFields(99L, 20L, 30L, req)
        );
    }

    @Test
    void testSubmissionFromAnotherTaskDenied() {
        mockUser(1L, SystemRole.BUSINESS_DEVELOPMENT_MANAGER);
        when(submissionRepository.findById(30L)).thenReturn(Optional.of(submission));
        
        FieldReviewRequest req = new FieldReviewRequest();
        // Request uses taskId 99L, but submission belongs to taskId 20L
        assertThrows(IllegalArgumentException.class, () ->
                service.reviewFields(10L, 99L, 30L, req)
        );
    }

    @Test
    void testTaskTypeOtherThanCompanyDataPreparationRejected() {
        mockUser(1L, SystemRole.BUSINESS_DEVELOPMENT_MANAGER);
        task.setTaskType(TaskType.ROLE_EVALUATION); // Not COMPANY_DATA_PREPARATION
        when(submissionRepository.findById(30L)).thenReturn(Optional.of(submission));
        
        FieldReviewRequest req = new FieldReviewRequest();
        
        assertThrows(com.apms.common.exception.BusinessValidationException.class, () ->
                service.reviewFields(10L, 20L, 30L, req)
        );
    }

    @Test
    void testTargetEntityMustMatchSubmission() {
        mockUser(1L, SystemRole.BUSINESS_DEVELOPMENT_MANAGER);
        when(submissionRepository.findById(30L)).thenReturn(Optional.of(submission));
        submission.setTargetEntityType(null); // Missing target entity
        
        FieldReviewRequest req = new FieldReviewRequest();
        
        assertThrows(com.apms.common.exception.BusinessValidationException.class, () ->
                service.reviewFields(10L, 20L, 30L, req)
        );
    }

    @Test
    void testStaffDenied() {
        mockUser(2L, SystemRole.BUSINESS_DEVELOPMENT_STAFF);
        when(submissionRepository.findById(30L)).thenReturn(Optional.of(submission));
        
        FieldReviewRequest req = new FieldReviewRequest();
        assertThrows(AccessDeniedException.class, () ->
                service.reviewFields(10L, 20L, 30L, req)
        );
    }

    @Test
    void testOwnerDenied() {
        mockUser(3L, SystemRole.BUSINESS_OWNER);
        when(submissionRepository.findById(30L)).thenReturn(Optional.of(submission));
        
        FieldReviewRequest req = new FieldReviewRequest();
        assertThrows(AccessDeniedException.class, () ->
                service.reviewFields(10L, 20L, 30L, req)
        );
    }

    @Test
    void testSystemAdminDeniedForCompanyDataPreparationFieldReview() {
        mockUser(4L, SystemRole.SYSTEM_ADMIN);
        when(submissionRepository.findById(30L)).thenReturn(Optional.of(submission));
        
        FieldReviewRequest req = new FieldReviewRequest();
        assertThrows(AccessDeniedException.class, () ->
                service.reviewFields(10L, 20L, 30L, req)
        );
    }

    @Test
    void testExistingSystemAdminReviewBehaviorForOtherTaskTypesRemainsUnchanged() {
        // Here we test reviewSubmission (not reviewFields) for a different task type
        mockUser(4L, SystemRole.SYSTEM_ADMIN);
        task.setTaskType(TaskType.ROLE_EVALUATION);
        when(submissionRepository.findById(30L)).thenReturn(Optional.of(submission));

        // We mock accountRepository since reviewSubmission looks up the reviewer
        @SuppressWarnings("unchecked")
        com.apms.domain.user.repository.sql.AccountRepository accountRepository = mock(com.apms.domain.user.repository.sql.AccountRepository.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "accountRepository", accountRepository);
        
        com.apms.domain.user.Account adminAcc = new com.apms.domain.user.Account();
        adminAcc.setId(4L);
        when(accountRepository.findById(4L)).thenReturn(Optional.of(adminAcc));

        // Mock auditLogService
        com.apms.domain.audit.service.AuditLogService auditLogService = mock(com.apms.domain.audit.service.AuditLogService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "auditLogService", auditLogService);

        // Mock ProjectTaskRepository
        com.apms.domain.project.repository.sql.ProjectTaskRepository taskRepository = mock(com.apms.domain.project.repository.sql.ProjectTaskRepository.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "taskRepository", taskRepository);

        // Mock approval handlers
        java.util.List<?> approvalHandlers = List.of();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "approvalHandlers", approvalHandlers);

        com.apms.domain.project.dto.ReviewTaskSubmissionRequest reviewReq = new com.apms.domain.project.dto.ReviewTaskSubmissionRequest();
        reviewReq.setDecision(com.apms.common.enums.ReviewDecision.APPROVE);
        
        // This should NOT throw AccessDeniedException or BusinessValidationException about TaskType.
        // It should proceed and save the submission (which we mock or verify).
        service.reviewSubmission(10L, 20L, 30L, reviewReq);
        
        verify(submissionRepository).save(submission);
    }
}
