package com.apms.domain.project.service;

import com.apms.common.enums.TaskStatus;
import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.project.ProjectKeyResult;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.dto.UpdateProjectTaskRequest;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import com.apms.security.UserDetailsImpl;
import com.apms.domain.user.Account;

import java.util.Optional;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
public class ProjectTaskServiceOkrTest {

    @Mock
    private ProjectTaskRepository projectTaskRepository;

    @Mock
    private com.apms.domain.project.repository.sql.ProjectRepository projectRepository;

    @Mock
    private com.apms.domain.project.repository.sql.ProjectMemberRepository projectMemberRepository;
    
    @Mock
    private com.apms.domain.user.repository.sql.AccountRepository accountRepository;

    @Mock
    private com.apms.domain.audit.service.AuditLogService auditLogService;

    @InjectMocks
    private ProjectTaskService projectTaskService;

    @BeforeEach
    void setUpSecurity() {
        Account account = new Account();
        account.setId(1L);
        account.setEmail("test@apms.com");
        UserDetailsImpl userDetails = new UserDetailsImpl(
                1L, "test@apms.com", "password", 
                List.of(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN")), 
                true);
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(userDetails);
        SecurityContext securityContext = mock(SecurityContext.class);
        when(securityContext.getAuthentication()).thenReturn(auth);
        SecurityContextHolder.setContext(securityContext);
    }

    @Test
    void okrGeneratedTaskCannotBeCancelled() {
        // Scenario 18
        ProjectTask task = new ProjectTask();
        task.setId(10L);
        task.setStatus(TaskStatus.AVAILABLE);
        ProjectKeyResult kr = new ProjectKeyResult();
        kr.setId(1L);
        task.setKeyResult(kr);
        com.apms.domain.project.Project project = new com.apms.domain.project.Project();
        project.setId(100L);
        task.setProject(project);

        when(projectTaskRepository.findById(10L)).thenReturn(Optional.of(task));

        UpdateProjectTaskRequest req = new UpdateProjectTaskRequest();
        req.setStatus(TaskStatus.CANCELLED);

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, 
            () -> projectTaskService.updateTask(100L, 10L, req));
            
        assertTrue(ex.getMessage().contains("OKR-generated tasks cannot be cancelled"));
    }

    @Test
    void legacyTaskCanStillUseExistingCancelBehavior() {
        // Scenario 19
        ProjectTask task = new ProjectTask();
        task.setId(10L);
        task.setStatus(TaskStatus.AVAILABLE);
        task.setKeyResult(null); // Legacy task
        com.apms.domain.project.Project project = new com.apms.domain.project.Project();
        project.setId(100L);
        task.setProject(project);

        when(projectTaskRepository.findById(10L)).thenReturn(Optional.of(task));
        when(projectTaskRepository.save(any())).thenReturn(task);

        UpdateProjectTaskRequest req = new UpdateProjectTaskRequest();
        req.setStatus(TaskStatus.CANCELLED);

        projectTaskService.updateTask(100L, 10L, req);
        
        // No exception thrown, status changed
        assertEquals(TaskStatus.CANCELLED, task.getStatus());
    }

    private void setStaffSecurityContext() {
        UserDetailsImpl userDetails = new UserDetailsImpl(
                1L, "test@apms.com", "password", 
                List.of(new SimpleGrantedAuthority("ROLE_BUSINESS_DEVELOPMENT_STAFF")), 
                true);
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(userDetails);
        SecurityContext securityContext = mock(SecurityContext.class);
        when(securityContext.getAuthentication()).thenReturn(auth);
        SecurityContextHolder.setContext(securityContext);
    }

    @Test
    void successfulClaimCreatesClaimAudit() {
        setStaffSecurityContext();
        ProjectTask task = new ProjectTask();
        task.setId(10L);
        com.apms.domain.project.Project project = new com.apms.domain.project.Project();
        project.setId(100L);
        task.setProject(project);

        when(projectRepository.existsByIdAndMembersAccountId(100L, 1L)).thenReturn(true);
        when(projectTaskRepository.findById(10L)).thenReturn(Optional.of(task));
        
        Account account = new Account();
        account.setId(1L);
        account.setEmail("test@apms.com");
        when(accountRepository.findById(1L)).thenReturn(Optional.of(account));
        
        when(projectTaskRepository.claimTaskAtomically(eq(10L), eq(100L), any(Account.class), eq(TaskStatus.AVAILABLE), eq(TaskStatus.IN_PROGRESS)))
            .thenReturn(1); // Success

        projectTaskService.claimTask(100L, 10L);

        verify(auditLogService).log(eq(1L), eq(com.apms.common.enums.AuditAction.PROJECT_TASK_CLAIMED), eq("ProjectTask"), eq("10"), anyString());
    }

    @Test
    void failedClaimDoesNotCreateClaimAudit() {
        setStaffSecurityContext();
        ProjectTask task = new ProjectTask();
        task.setId(10L);
        com.apms.domain.project.Project project = new com.apms.domain.project.Project();
        project.setId(100L);
        task.setProject(project);

        when(projectRepository.existsByIdAndMembersAccountId(100L, 1L)).thenReturn(true);
        when(projectTaskRepository.findById(10L)).thenReturn(Optional.of(task));
        
        Account account = new Account();
        account.setId(1L);
        account.setEmail("test@apms.com");
        when(accountRepository.findById(1L)).thenReturn(Optional.of(account));
        
        when(projectTaskRepository.claimTaskAtomically(eq(10L), eq(100L), any(Account.class), eq(TaskStatus.AVAILABLE), eq(TaskStatus.IN_PROGRESS)))
            .thenReturn(0); // Failed (already claimed)

        assertThrows(com.apms.common.exception.BusinessConflictException.class, () -> projectTaskService.claimTask(100L, 10L));

        verify(auditLogService, never()).log(anyLong(), eq(com.apms.common.enums.AuditAction.PROJECT_TASK_CLAIMED), anyString(), anyString(), anyString());
    }

    @Test
    void successfulReleaseCreatesReleaseAudit() {
        setStaffSecurityContext();
        ProjectTask task = new ProjectTask();
        task.setId(10L);
        com.apms.domain.project.Project project = new com.apms.domain.project.Project();
        project.setId(100L);
        task.setProject(project);

        when(projectRepository.existsByIdAndMembersAccountId(100L, 1L)).thenReturn(true);
        when(projectTaskRepository.findById(10L)).thenReturn(Optional.of(task));
        
        when(projectTaskRepository.releaseTaskAtomically(eq(10L), eq(100L), eq(1L), eq(TaskStatus.IN_PROGRESS), eq(TaskStatus.AVAILABLE)))
            .thenReturn(1); // Success

        projectTaskService.releaseTask(100L, 10L);

        verify(auditLogService).log(eq(1L), eq(com.apms.common.enums.AuditAction.PROJECT_TASK_RELEASED), eq("ProjectTask"), eq("10"), anyString());
    }

    @Test
    void failedReleaseDoesNotCreateReleaseAudit() {
        setStaffSecurityContext();
        ProjectTask task = new ProjectTask();
        task.setId(10L);
        com.apms.domain.project.Project project = new com.apms.domain.project.Project();
        project.setId(100L);
        task.setProject(project);

        when(projectRepository.existsByIdAndMembersAccountId(100L, 1L)).thenReturn(true);
        when(projectTaskRepository.findById(10L)).thenReturn(Optional.of(task));
        
        when(projectTaskRepository.releaseTaskAtomically(eq(10L), eq(100L), eq(1L), eq(TaskStatus.IN_PROGRESS), eq(TaskStatus.AVAILABLE)))
            .thenReturn(0); // Failed

        assertThrows(com.apms.common.exception.BusinessValidationException.class, () -> projectTaskService.releaseTask(100L, 10L));

        verify(auditLogService, never()).log(anyLong(), eq(com.apms.common.enums.AuditAction.PROJECT_TASK_RELEASED), anyString(), anyString(), anyString());
    }
}
