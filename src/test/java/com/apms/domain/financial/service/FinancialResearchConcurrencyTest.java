package com.apms.domain.financial.service;

import com.apms.common.security.StaffCompanyScopeEvaluator;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.document.repository.mongo.RawDocumentRepository;
import com.apms.domain.document.service.DocumentService;
import com.apms.domain.financial.FinancialResearch;
import com.apms.domain.financial.FinancialResearchStatus;
import com.apms.domain.financial.dto.FinancialResearchResponse;
import com.apms.domain.financial.repository.FinancialResearchRepository;
import com.apms.domain.profile.service.CompanyIdentityResolver;
import com.apms.domain.profile.service.CompanyProfileAccessService;
import com.apms.domain.profile.service.CompanyProfileFinancialService;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.domain.project.repository.sql.ProjectTaskSubmissionRepository;
import com.apms.domain.user.repository.sql.AccountRepository;
import com.apms.domain.user.repository.sql.UserProfileRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.util.ArrayList;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FinancialResearchConcurrencyTest {

    @Mock
    private FinancialResearchRepository researchRepository;
    @Mock
    private RawDocumentRepository documentRepository;
    @Mock
    private DocumentService documentService;
    @Mock
    private ProjectTaskRepository projectTaskRepository;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private DocumentCompanyMatcher companyMatcher;
    @Mock
    private ProjectTaskSubmissionRepository submissionRepository;
    @Mock
    private FinancialExtractionService extractionService;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private UserProfileRepository userProfileRepository;
    @Mock
    private AccountRepository accountRepository;
    @Mock
    private CompanyIdentityResolver companyIdentityResolver;
    @Mock
    private CompanyProfileAccessService companyProfileAccessService;
    @Mock
    private StaffCompanyScopeEvaluator companyScope;
    @Mock
    private CompanyProfileFinancialService companyProfileFinancialService;

    @InjectMocks
    private FinancialResearchService researchService;

    @Test
    @DisplayName("When save throws DuplicateKeyException due to concurrent creation, getResearch recovers and returns the existing document")
    void testGetResearchRecoversFromConcurrentDuplicateKeyException() {
        Long projectId = 10L;
        Long taskId = 200L;

        FinancialResearch existingSavedResearch = FinancialResearch.builder()
                .id("mongo-id-123")
                .taskId(taskId)
                .projectId(projectId)
                .status(FinancialResearchStatus.DRAFT)
                .reports(new ArrayList<>())
                .metrics(new ArrayList<>())
                .build();

        // 1. First findByTaskId returns empty (neither thread has created it yet)
        // 2. Second findByTaskId in catch block returns the concurrently saved document
        when(researchRepository.findByTaskId(taskId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existingSavedResearch));

        // When saving, simulate MongoDB throwing DuplicateKeyException (concurrent thread saved first)
        when(researchRepository.save(any(FinancialResearch.class)))
                .thenThrow(new DuplicateKeyException("E11000 duplicate key error collection: apms.financial_researches index: taskId_1 dup key: { taskId: 200 }"));

        when(projectTaskRepository.findWithProjectById(taskId)).thenReturn(Optional.empty());

        // Call getResearch
        Optional<FinancialResearchResponse> result = researchService.getResearch(projectId, taskId);

        // Verify successful recovery
        assertThat(result).isPresent();
        assertThat(result.get().getTaskId()).isEqualTo(taskId);
        assertThat(result.get().getId()).isEqualTo("mongo-id-123");

        // Verify save was attempted and recovery find was invoked
        verify(researchRepository, times(1)).save(any(FinancialResearch.class));
        verify(researchRepository, times(2)).findByTaskId(taskId);
    }
}
