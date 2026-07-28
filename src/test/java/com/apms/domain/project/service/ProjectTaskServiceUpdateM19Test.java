package com.apms.domain.project.service;

import com.apms.common.enums.TaskStatus;
import com.apms.domain.project.Project;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.dto.UpdateProjectTaskRequest;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.document.service.DocumentService;
import com.apms.domain.ai.repository.mongo.AiExtractionCacheRepository;
import com.apms.domain.candidate.repository.mongo.CompanyCandidateRepository;
import com.apms.domain.profile.repository.mongo.CompanyProfileUpdateProposalRepository;
import com.apms.domain.project.repository.sql.ProjectTaskSubmissionRepository;
import com.apms.domain.project.repository.sql.ProjectTaskDraftRepository;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.user.Account;
import com.apms.domain.user.repository.sql.AccountRepository;
import com.apms.security.UserDetailsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProjectTaskServiceUpdateM19Test {

    @Mock private ProjectTaskRepository projectTaskRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private AuditLogService auditLogService;
    @Mock private DocumentService documentService;
    @Mock private AiExtractionCacheRepository extractionCacheRepository;
    @Mock private CompanyCandidateRepository candidateRepository;
    @Mock private CompanyProfileUpdateProposalRepository proposalRepository;
    @Mock private ProjectTaskSubmissionRepository submissionRepository;
    @Mock private ProjectTaskDraftRepository draftRepository;
    @Mock private CompanyProfileRepository companyProfileRepository;
    @Mock private SecurityContext securityContext;
    @Mock private Authentication authentication;

    @InjectMocks
    private ProjectTaskService projectTaskService;

    private Project project;
    private ProjectTask unassignedTask;
    private Account staffAccount;

    @BeforeEach
    void setUp() {
        project = new Project();
        project.setId(1L);

        unassignedTask = new ProjectTask();
        unassignedTask.setId(100L);
        unassignedTask.setProject(project);
        unassignedTask.setStatus(TaskStatus.TODO);
        unassignedTask.setAssignedToAccount(null);

        staffAccount = new Account();
        staffAccount.setId(999L);

        when(projectTaskRepository.findById(100L)).thenReturn(Optional.of(unassignedTask));
    }

    @SuppressWarnings("unchecked")
    private void setupStaffUser() {
        UserDetailsImpl user = mock(UserDetailsImpl.class);
        when(user.getId()).thenReturn(999L);
        Collection<? extends org.springframework.security.core.GrantedAuthority> authorities = List.of(
                new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_BUSINESS_DEVELOPMENT_STAFF")
        );
        when(user.getAuthorities()).thenAnswer(inv -> authorities);

        when(authentication.getPrincipal()).thenReturn(user);
        when(securityContext.getAuthentication()).thenReturn(authentication);
        SecurityContextHolder.setContext(securityContext);
    }

    @Test
    void updateTask_unassignedTaskWithReassignment_throwsAccessDenied_noNPE() {
        setupStaffUser();

        UpdateProjectTaskRequest request = new UpdateProjectTaskRequest();
        request.setAssignedToUserId(500L);

        AccessDeniedException ex = assertThrows(AccessDeniedException.class,
                () -> projectTaskService.updateTask(1L, 100L, request));

        assertNotNull(ex.getMessage());
        verify(projectTaskRepository, never()).save(any());
    }

    @Test
    void updateTask_unassignedTaskWithReassignment_noNPE_occurs() {
        setupStaffUser();

        UpdateProjectTaskRequest request = new UpdateProjectTaskRequest();
        request.setAssignedToUserId(500L);

        assertDoesNotThrow(() -> {
            try {
                projectTaskService.updateTask(1L, 100L, request);
            } catch (AccessDeniedException e) {
                // Expected — guard is working, not an NPE
            }
        });
    }
}
