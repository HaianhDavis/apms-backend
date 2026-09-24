package com.apms.domain.news.service;

import com.apms.common.enums.SystemRole;
import com.apms.common.enums.TaskStatus;
import com.apms.common.enums.TaskType;
import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.document.service.StorageService;
import com.apms.domain.news.dto.CreateNewsResearchDraftRequest;
import com.apms.domain.news.entity.CompanyNewsResearchDraft;
import com.apms.domain.news.repository.CompanyNewsResearchDraftRepository;
import com.apms.domain.project.Project;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.domain.user.Account;
import com.apms.security.UserDetailsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompanyNewsResearchDraftServiceTest {

    @Mock
    private CompanyNewsResearchDraftRepository draftRepository;
    @Mock
    private ProjectTaskRepository taskRepository;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private StorageService storageService;

    @InjectMocks
    private CompanyNewsResearchDraftService draftService;

    @Mock
    private SecurityContext securityContext;
    @Mock
    private Authentication authentication;

    private UserDetailsImpl staffUser;
    private ProjectTask task;
    private Project project;

    @BeforeEach
    void setUp() {
        staffUser = new UserDetailsImpl(
                1L, "staff@apms.com", "pass",
                List.of(new SimpleGrantedAuthority("ROLE_BUSINESS_DEVELOPMENT_STAFF")),
                true
        );

        Account staffAccount = Account.builder().id(1L).email("staff@apms.com").build();
        project = Project.builder().id(100L).build();
        task = ProjectTask.builder()
                .id(200L)
                .project(project)
                .taskType(TaskType.COMPANY_NEWS_RESEARCH)
                .status(TaskStatus.IN_PROGRESS)
                .assignedToAccount(staffAccount)
                .targetCompanyProfileId("profile-123")
                .build();
    }

    private void mockSecurityContext(UserDetailsImpl user) {
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.getPrincipal()).thenReturn(user);
        SecurityContextHolder.setContext(securityContext);
    }

    @Test
    void createDraft_Success() {
        mockSecurityContext(staffUser);
        when(taskRepository.findById(200L)).thenReturn(Optional.of(task));
        when(projectRepository.existsByIdAndMembersAccountId(100L, 1L)).thenReturn(true);

        CompanyNewsResearchDraft savedDraft = CompanyNewsResearchDraft.builder().id("draft-1").build();
        when(draftRepository.save(any(CompanyNewsResearchDraft.class))).thenReturn(savedDraft);

        CreateNewsResearchDraftRequest req = new CreateNewsResearchDraftRequest();
        req.setTitle("News Title");
        req.setContent("Content");
        req.setPublishedAt(LocalDateTime.now());
        req.setSourceUrl("https://example.com");

        var response = draftService.createDraft(100L, 200L, req);

        assertNotNull(response);
        verify(draftRepository).save(any(CompanyNewsResearchDraft.class));
    }

    @Test
    void createDraft_FailsIfWrongTaskType() {
        mockSecurityContext(staffUser);
        task.setTaskType(TaskType.GENERAL_TASK);
        when(taskRepository.findById(200L)).thenReturn(Optional.of(task));

        assertThrows(BusinessValidationException.class, () -> 
                draftService.createDraft(100L, 200L, new CreateNewsResearchDraftRequest()));
    }

    @Test
    void createDraft_FailsIfNotAssignedStaff() {
        UserDetailsImpl otherStaff = new UserDetailsImpl(
                2L, "other@apms.com", "pass",
                List.of(new SimpleGrantedAuthority("ROLE_BUSINESS_DEVELOPMENT_STAFF")),
                true
        );
        mockSecurityContext(otherStaff);
        
        when(taskRepository.findById(200L)).thenReturn(Optional.of(task));
        when(projectRepository.existsByIdAndMembersAccountId(100L, 2L)).thenReturn(true);

        assertThrows(AccessDeniedException.class, () -> 
                draftService.createDraft(100L, 200L, new CreateNewsResearchDraftRequest()));
    }
}
