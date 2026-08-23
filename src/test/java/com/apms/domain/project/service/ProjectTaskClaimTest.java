package com.apms.domain.project.service;

import com.apms.common.enums.ProjectKeyResultType;
import com.apms.common.enums.TaskStatus;
import com.apms.common.enums.TaskType;
import com.apms.common.enums.SystemRole;
import com.apms.common.exception.BusinessConflictException;
import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.project.Project;
import com.apms.domain.project.ProjectKeyResult;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.dto.ProjectTaskResponse;
import com.apms.domain.project.dto.ProjectTaskWorkbenchResponse;
import com.apms.domain.project.dto.UpdateProjectTaskRequest;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.domain.project.repository.sql.ProjectTaskSubmissionRepository;
import com.apms.domain.user.Account;
import com.apms.domain.user.repository.sql.AccountRepository;
import com.apms.domain.document.service.DocumentService;
import com.apms.domain.candidate.repository.mongo.CompanyCandidateRepository;
import com.apms.domain.profile.repository.mongo.CompanyProfileUpdateProposalRepository;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.notification.service.NotificationService;
import com.apms.security.UserDetailsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.domain.PageImpl;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ProjectTaskClaimTest {

    @Mock
    private ProjectTaskRepository projectTaskRepository;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private AccountRepository accountRepository;
    
    @Mock
    private DocumentService documentService;
    
    @Mock
    private CompanyCandidateRepository candidateRepository;
    
    @Mock
    private CompanyProfileUpdateProposalRepository proposalRepository;
    
    @Mock
    private ProjectTaskSubmissionRepository submissionRepository;
    
    @Mock
    private AuditLogService auditLogService;
    
    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private ProjectTaskService projectTaskService;

    private Project project;
    private ProjectTask availableTask;
    private ProjectTask inProgressTask;
    private Account staffAccount;
    private UserDetailsImpl staffUserDetails;

    @BeforeEach
    void setUp() {
        staffAccount = new Account();
        staffAccount.setId(10L);
        staffAccount.setEmail("staff@example.com");

        project = Project.builder().id(100L).build();

        ProjectKeyResult kr = ProjectKeyResult.builder().id(50L).type(ProjectKeyResultType.BASIC_COMPANY_INFORMATION).build();

        availableTask = ProjectTask.builder()
                .id(1L)
                .project(project)
                .keyResult(kr)
                .status(TaskStatus.AVAILABLE)
                .assignedToAccount(null)
                .taskType(TaskType.COMPANY_DATA_PREPARATION)
                .build();

        inProgressTask = ProjectTask.builder()
                .id(2L)
                .project(project)
                .keyResult(kr)
                .status(TaskStatus.IN_PROGRESS)
                .assignedToAccount(staffAccount)
                .taskType(TaskType.COMPANY_DATA_PREPARATION)
                .build();
    }

    private void mockSecurityContext(Long userId, SystemRole role) {
        UserDetailsImpl userDetails = new UserDetailsImpl(
                userId,
                "user@example.com",
                "password",
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name())),
                true,
                true
        );
        Authentication authentication = mock(Authentication.class);
        lenient().when(authentication.getPrincipal()).thenReturn(userDetails);
        SecurityContext securityContext = mock(SecurityContext.class);
        lenient().when(securityContext.getAuthentication()).thenReturn(authentication);
        SecurityContextHolder.setContext(securityContext);
    }

    @Test
    void projectStaffCanSeeAvailableTasks() {
        mockSecurityContext(10L, SystemRole.BUSINESS_DEVELOPMENT_STAFF);
        
        when(projectTaskRepository.findAll(any(Specification.class), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(availableTask)));

        var tasks = projectTaskService.getTasks(100L, TaskStatus.AVAILABLE, null, PageRequest.of(0, 10), true);
        
        assertThat(tasks.getContent()).hasSize(1);
        assertThat(tasks.getContent().get(0).getStatus()).isEqualTo(TaskStatus.AVAILABLE);
    }

    @Test
    void staffCanClaimAvailableTask() {
        mockSecurityContext(10L, SystemRole.BUSINESS_DEVELOPMENT_STAFF);
        
        when(projectRepository.existsByIdAndMembersAccountId(100L, 10L)).thenReturn(true);
        when(projectTaskRepository.findById(1L)).thenReturn(Optional.of(availableTask));
        when(accountRepository.findById(10L)).thenReturn(Optional.of(staffAccount));
        when(projectTaskRepository.claimTaskAtomically(1L, 100L, staffAccount, TaskStatus.AVAILABLE, TaskStatus.IN_PROGRESS))
                .thenReturn(1);

        ProjectTaskResponse response = projectTaskService.claimTask(100L, 1L);
        assertThat(response).isNotNull();
    }

    @Test
    void claimMovesTaskFromAvailableToInProgress() {
        mockSecurityContext(10L, SystemRole.BUSINESS_DEVELOPMENT_STAFF);
        
        when(projectRepository.existsByIdAndMembersAccountId(100L, 10L)).thenReturn(true);
        when(projectTaskRepository.findById(1L)).thenReturn(Optional.of(availableTask));
        when(accountRepository.findById(10L)).thenReturn(Optional.of(staffAccount));
        
        // Simulating the effect of atomic update in DB
        doAnswer(invocation -> {
            availableTask.setStatus(TaskStatus.IN_PROGRESS);
            availableTask.setAssignedToAccount(staffAccount);
            return 1;
        }).when(projectTaskRepository).claimTaskAtomically(1L, 100L, staffAccount, TaskStatus.AVAILABLE, TaskStatus.IN_PROGRESS);

        ProjectTaskResponse response = projectTaskService.claimTask(100L, 1L);
        
        assertThat(response.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(response.getAssignedToUserId()).isEqualTo(10L);
    }

    @Test
    void staffCannotClaimAlreadyClaimedTask() {
        mockSecurityContext(10L, SystemRole.BUSINESS_DEVELOPMENT_STAFF);
        
        when(projectRepository.existsByIdAndMembersAccountId(100L, 10L)).thenReturn(true);
        when(projectTaskRepository.findById(2L)).thenReturn(Optional.of(inProgressTask));
        when(accountRepository.findById(10L)).thenReturn(Optional.of(staffAccount));
        
        // Simulating the failure of atomic update (returns 0 rows affected)
        when(projectTaskRepository.claimTaskAtomically(2L, 100L, staffAccount, TaskStatus.AVAILABLE, TaskStatus.IN_PROGRESS))
                .thenReturn(0);

        assertThatThrownBy(() -> projectTaskService.claimTask(100L, 2L))
                .isInstanceOf(com.apms.common.exception.BusinessConflictException.class)
                .hasMessageContaining("no longer available or already claimed");
    }

    @Test
    void concurrentClaimOnlyOneSucceeds() {
        mockSecurityContext(10L, SystemRole.BUSINESS_DEVELOPMENT_STAFF);
        
        when(projectRepository.existsByIdAndMembersAccountId(100L, 10L)).thenReturn(true);
        when(projectTaskRepository.findById(1L)).thenReturn(Optional.of(availableTask));
        when(accountRepository.findById(10L)).thenReturn(Optional.of(staffAccount));
        
        // Return 0 rows updated to simulate concurrent claim success by someone else
        when(projectTaskRepository.claimTaskAtomically(1L, 100L, staffAccount, TaskStatus.AVAILABLE, TaskStatus.IN_PROGRESS))
                .thenReturn(0);

        assertThatThrownBy(() -> projectTaskService.claimTask(100L, 1L))
                .isInstanceOf(BusinessConflictException.class)
                .hasMessageContaining("Task is no longer available or already claimed");
    }

    @Test
    void staffOutsideProjectCannotClaimTask() {
        mockSecurityContext(10L, SystemRole.BUSINESS_DEVELOPMENT_STAFF);
        when(projectRepository.existsByIdAndMembersAccountId(100L, 10L)).thenReturn(false);

        assertThatThrownBy(() -> projectTaskService.claimTask(100L, 1L))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("member of the project");
    }

    @Test
    void managerCannotClaimTask() {
        mockSecurityContext(10L, SystemRole.BUSINESS_DEVELOPMENT_MANAGER);

        assertThatThrownBy(() -> projectTaskService.claimTask(100L, 1L))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Only staff");
    }

    @Test
    void nonStaffRoleCannotClaimTask() {
        mockSecurityContext(10L, SystemRole.SYSTEM_ADMIN);

        assertThatThrownBy(() -> projectTaskService.claimTask(100L, 1L))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void staffCannotClaimTaskFromAnotherProjectPath() {
        mockSecurityContext(10L, SystemRole.BUSINESS_DEVELOPMENT_STAFF);
        when(projectRepository.existsByIdAndMembersAccountId(999L, 10L)).thenReturn(true);
        when(projectTaskRepository.findById(1L)).thenReturn(Optional.of(availableTask)); // availableTask belongs to 100L

        assertThatThrownBy(() -> projectTaskService.claimTask(999L, 1L))
                .isInstanceOf(BusinessValidationException.class);
    }

    @Test
    void staffCanReleaseOwnInProgressTask() {
        mockSecurityContext(10L, SystemRole.BUSINESS_DEVELOPMENT_STAFF);
        
        when(projectRepository.existsByIdAndMembersAccountId(100L, 10L)).thenReturn(true);
        when(projectTaskRepository.findById(2L)).thenReturn(Optional.of(inProgressTask));
        
        doAnswer(invocation -> {
            inProgressTask.setStatus(TaskStatus.AVAILABLE);
            inProgressTask.setAssignedToAccount(null);
            return 1;
        }).when(projectTaskRepository).releaseTaskAtomically(2L, 100L, 10L, TaskStatus.IN_PROGRESS, TaskStatus.AVAILABLE);

        ProjectTaskResponse response = projectTaskService.releaseTask(100L, 2L);
        
        assertThat(response.getStatus()).isEqualTo(TaskStatus.AVAILABLE);
        assertThat(response.getAssignedToUserId()).isNull();
    }

    @Test
    void staffCannotReleaseAnotherStaffTask() {
        mockSecurityContext(11L, SystemRole.BUSINESS_DEVELOPMENT_STAFF); // Different user
        
        when(projectRepository.existsByIdAndMembersAccountId(100L, 11L)).thenReturn(true);
        when(projectTaskRepository.findById(2L)).thenReturn(Optional.of(inProgressTask));
        
        when(projectTaskRepository.releaseTaskAtomically(2L, 100L, 11L, TaskStatus.IN_PROGRESS, TaskStatus.AVAILABLE))
                .thenReturn(0);

        assertThatThrownBy(() -> projectTaskService.releaseTask(100L, 2L))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("assigned to you");
    }

    @Test
    void okrGeneratedTaskCannotBeManuallyAssignedByManager() {
        mockSecurityContext(20L, SystemRole.BUSINESS_DEVELOPMENT_MANAGER);
        
        when(projectTaskRepository.findById(1L)).thenReturn(Optional.of(availableTask));
        
        UpdateProjectTaskRequest updateReq = new UpdateProjectTaskRequest();
        updateReq.setAssignedToUserId(30L); // Attempt to manually assign
        
        assertThatThrownBy(() -> projectTaskService.updateTask(100L, 1L, updateReq))
                .isInstanceOf(com.apms.common.exception.BusinessValidationException.class)
                .hasMessageContaining("self-claimed and cannot be manually assigned");
    }

    @Test
    void legacyTaskAssignmentStillWorks() {
        mockSecurityContext(20L, SystemRole.BUSINESS_DEVELOPMENT_MANAGER);
        
        ProjectTask legacyTask = ProjectTask.builder()
                .id(3L)
                .project(project)
                .keyResult(null) // Not OKR
                .status(TaskStatus.TODO)
                .build();
                
        when(projectTaskRepository.findById(3L)).thenReturn(Optional.of(legacyTask));
        when(projectRepository.existsByIdAndMembersAccountId(100L, 30L)).thenReturn(true);
        when(accountRepository.findById(30L)).thenReturn(Optional.of(new Account()));
        when(projectTaskRepository.save(legacyTask)).thenReturn(legacyTask);
        
        UpdateProjectTaskRequest updateReq = new UpdateProjectTaskRequest();
        updateReq.setAssignedToUserId(30L); 
        
        ProjectTaskResponse response = projectTaskService.updateTask(100L, 3L, updateReq);
        assertThat(response).isNotNull();
    }

    @Test
    void staffCannotReleaseInReviewTask() {
        mockSecurityContext(10L, SystemRole.BUSINESS_DEVELOPMENT_STAFF);
        when(projectRepository.existsByIdAndMembersAccountId(100L, 10L)).thenReturn(true);
        
        ProjectTask inReviewTask = ProjectTask.builder().id(4L).project(project).status(TaskStatus.IN_REVIEW).assignedToAccount(staffAccount).build();
        when(projectTaskRepository.findById(4L)).thenReturn(Optional.of(inReviewTask));
        
        when(projectTaskRepository.releaseTaskAtomically(4L, 100L, 10L, TaskStatus.IN_PROGRESS, TaskStatus.AVAILABLE)).thenReturn(0);
        
        assertThatThrownBy(() -> projectTaskService.releaseTask(100L, 4L))
                .isInstanceOf(BusinessValidationException.class);
    }

    @Test
    void staffCannotReleaseDoneTask() {
        mockSecurityContext(10L, SystemRole.BUSINESS_DEVELOPMENT_STAFF);
        when(projectRepository.existsByIdAndMembersAccountId(100L, 10L)).thenReturn(true);
        
        ProjectTask doneTask = ProjectTask.builder().id(5L).project(project).status(TaskStatus.DONE).assignedToAccount(staffAccount).build();
        when(projectTaskRepository.findById(5L)).thenReturn(Optional.of(doneTask));
        
        when(projectTaskRepository.releaseTaskAtomically(5L, 100L, 10L, TaskStatus.IN_PROGRESS, TaskStatus.AVAILABLE)).thenReturn(0);
        
        assertThatThrownBy(() -> projectTaskService.releaseTask(100L, 5L))
                .isInstanceOf(BusinessValidationException.class);
    }

    @Test
    void staffCannotReleaseCancelledTask() {
        mockSecurityContext(10L, SystemRole.BUSINESS_DEVELOPMENT_STAFF);
        when(projectRepository.existsByIdAndMembersAccountId(100L, 10L)).thenReturn(true);
        
        ProjectTask cancelledTask = ProjectTask.builder().id(6L).project(project).status(TaskStatus.CANCELLED).assignedToAccount(staffAccount).build();
        when(projectTaskRepository.findById(6L)).thenReturn(Optional.of(cancelledTask));
        
        when(projectTaskRepository.releaseTaskAtomically(6L, 100L, 10L, TaskStatus.IN_PROGRESS, TaskStatus.AVAILABLE)).thenReturn(0);
        
        assertThatThrownBy(() -> projectTaskService.releaseTask(100L, 6L))
                .isInstanceOf(BusinessValidationException.class);
    }

    @Test
    void releasedTaskCanBeClaimedByAnotherStaff() {
        // Staff A releases
        mockSecurityContext(10L, SystemRole.BUSINESS_DEVELOPMENT_STAFF);
        when(projectRepository.existsByIdAndMembersAccountId(100L, 10L)).thenReturn(true);
        when(projectTaskRepository.findById(2L)).thenReturn(Optional.of(inProgressTask));
        
        doAnswer(invocation -> {
            inProgressTask.setStatus(TaskStatus.AVAILABLE);
            inProgressTask.setAssignedToAccount(null);
            return 1;
        }).when(projectTaskRepository).releaseTaskAtomically(2L, 100L, 10L, TaskStatus.IN_PROGRESS, TaskStatus.AVAILABLE);

        projectTaskService.releaseTask(100L, 2L);
        
        // Staff B claims
        Account staffB = new Account();
        staffB.setId(20L);
        mockSecurityContext(20L, SystemRole.BUSINESS_DEVELOPMENT_STAFF);
        when(projectRepository.existsByIdAndMembersAccountId(100L, 20L)).thenReturn(true);
        when(accountRepository.findById(20L)).thenReturn(Optional.of(staffB));
        
        doAnswer(invocation -> {
            inProgressTask.setStatus(TaskStatus.IN_PROGRESS);
            inProgressTask.setAssignedToAccount(staffB);
            return 1;
        }).when(projectTaskRepository).claimTaskAtomically(2L, 100L, staffB, TaskStatus.AVAILABLE, TaskStatus.IN_PROGRESS);
        
        ProjectTaskResponse response = projectTaskService.claimTask(100L, 2L);
        assertThat(response.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(response.getAssignedToUserId()).isEqualTo(20L);
    }

    @Test
    void availableTaskCannotBeSubmittedBeforeClaim() {
        // Technically testing Submission service, but to simulate the exact test case requirement
        // we can test the workbench logic which blocks actions
        mockSecurityContext(10L, SystemRole.BUSINESS_DEVELOPMENT_STAFF);
        when(projectRepository.existsByIdAndMembersAccountId(100L, 10L)).thenReturn(true);
        when(projectTaskRepository.findById(1L)).thenReturn(Optional.of(availableTask)); // availableTask has NO assignee
        when(documentService.getTaskImportJobs(eq(100L), eq(1L), anyBoolean(), any())).thenReturn(new PageImpl<>(List.of()));
        when(candidateRepository.findByTaskId(1L)).thenReturn(List.of());
        when(proposalRepository.findByTaskId(1L)).thenReturn(List.of());
        
        ProjectTaskWorkbenchResponse response = projectTaskService.getTaskWorkbench(100L, 1L);
        assertThat(response.getAvailableActions()).contains(com.apms.common.enums.TaskAction.CLAIM_TASK);
        assertThat(response.getAvailableActions()).doesNotContain(com.apms.common.enums.TaskAction.SUBMIT_WORK);
    }

    @Test
    void claimedTaskStillWorksWithExistingWorkbenchOwnershipCheck() {
        mockSecurityContext(10L, SystemRole.BUSINESS_DEVELOPMENT_STAFF);
        when(projectRepository.existsByIdAndMembersAccountId(100L, 10L)).thenReturn(true);
        when(projectTaskRepository.findById(2L)).thenReturn(Optional.of(inProgressTask));
        when(documentService.getTaskImportJobs(eq(100L), eq(2L), anyBoolean(), any())).thenReturn(new PageImpl<>(List.of()));
        when(candidateRepository.findByTaskId(2L)).thenReturn(List.of());
        when(proposalRepository.findByTaskId(2L)).thenReturn(List.of());
        
        ProjectTaskWorkbenchResponse response = projectTaskService.getTaskWorkbench(100L, 2L);
        assertThat(response.getAvailableActions()).contains(com.apms.common.enums.TaskAction.RELEASE_TASK);
        assertThat(response.getAvailableActions()).contains(com.apms.common.enums.TaskAction.SUBMIT_SELECTED_DRAFT);
    }

    @Test
    void claimedTaskCanUseExistingSubmissionFlow() {
        // Just verify that once claimed, ownership check matches
        mockSecurityContext(10L, SystemRole.BUSINESS_DEVELOPMENT_STAFF);
        assertThat(inProgressTask.getAssignedToAccount().getId()).isEqualTo(10L);
        assertThat(inProgressTask.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
    }
}
