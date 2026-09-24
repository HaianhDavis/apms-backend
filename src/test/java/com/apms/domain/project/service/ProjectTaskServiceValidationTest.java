package com.apms.domain.project.service;

import com.apms.common.enums.TaskType;
import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.project.Project;
import com.apms.domain.project.dto.CreateProjectTaskRequest;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.domain.user.Account;
import com.apms.domain.user.repository.sql.AccountRepository;
import com.apms.security.UserDetailsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ProjectTaskServiceValidationTest {

    @Mock
    private ProjectTaskRepository projectTaskRepository;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private AccountRepository accountRepository;
    @Mock
    private CompanyProfileRepository companyProfileRepository;
    @Mock
    private com.apms.domain.audit.service.AuditLogService auditLogService;

    @InjectMocks
    private ProjectTaskService projectTaskService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        SecurityContext ctx = mock(SecurityContext.class);
        Authentication auth = mock(Authentication.class);
        UserDetailsImpl user = new UserDetailsImpl(1L, "test@test.com", "pass", java.util.Collections.emptyList(), true);
        when(auth.getPrincipal()).thenReturn(user);
        when(ctx.getAuthentication()).thenReturn(auth);
        SecurityContextHolder.setContext(ctx);

        when(accountRepository.findById(1L)).thenReturn(Optional.of(new Account()));
    }

    @Test
    void createTask_missingTargetProfileForContractTask_rejected() {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(new Project()));

        CreateProjectTaskRequest req = new CreateProjectTaskRequest();
        req.setTaskType(TaskType.PARTNER_CONTRACT_COLLECTION);
        // targetCompanyProfileId is missing

        assertThrows(BusinessValidationException.class, () -> projectTaskService.createTask(1L, req));
    }

    @Test
    void createTask_documentCollection_rejected() {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(new Project()));

        CreateProjectTaskRequest req = new CreateProjectTaskRequest();
        req.setTaskType(TaskType.DOCUMENT_COLLECTION);

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () -> projectTaskService.createTask(1L, req));
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("DOCUMENT_COLLECTION is deprecated"));
    }

    @Test
    void createTask_companyDataPreparation_succeeds() {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(new Project()));
        when(projectTaskRepository.save(any())).thenAnswer(inv -> {
            com.apms.domain.project.ProjectTask t = inv.getArgument(0);
            t.setId(10L);
            return t;
        });

        CreateProjectTaskRequest req = new CreateProjectTaskRequest();
        req.setTaskType(TaskType.COMPANY_DATA_PREPARATION);

        com.apms.domain.project.dto.ProjectTaskResponse resp = projectTaskService.createTask(1L, req);
        org.junit.jupiter.api.Assertions.assertNotNull(resp);
        org.junit.jupiter.api.Assertions.assertEquals(TaskType.COMPANY_DATA_PREPARATION, resp.getTaskType());
    }


}
