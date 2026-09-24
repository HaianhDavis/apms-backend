package com.apms.domain.ai.service;

import com.apms.common.enums.AiExtractionJobStatus;
import com.apms.common.enums.SystemRole;
import com.apms.common.enums.TaskStatus;
import com.apms.common.enums.TaskType;
import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.ai.entity.AiExtractionJob;
import com.apms.domain.ai.repository.AiExtractionJobRepository;
import com.apms.domain.candidate.repository.mongo.CompanyCandidateRepository;
import com.apms.domain.candidate.service.CandidateService;
import com.apms.domain.document.RawDocument;
import com.apms.domain.document.repository.mongo.RawDocumentRepository;
import com.apms.domain.financial.service.DocumentCompanyMatcher;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.project.Project;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.domain.user.Account;
import com.apms.domain.user.repository.sql.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaskExtractionSecurityTest {

    @Mock private RawDocumentRepository rawDocumentRepository;
    @Mock private com.apms.domain.ai.service.provider.GeminiExtractionProvider geminiProvider;
    @Mock private CompanyCandidateRepository candidateRepository;
    @Mock private CandidateService candidateService;
    @Mock private AiExtractionJobRepository jobRepository;
    @Mock private AiExtractionQualityService qualityService;
    @Mock private ProjectTaskRepository projectTaskRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private DocumentCompanyConsistencyValidator companyConsistencyValidator;
    @Mock private CompanyProfileRepository companyProfileRepository;
    @Mock private DocumentCompanyMatcher companyMatcher;
    @Mock private CompanyIdentityDetectionService identityDetectionService;

    @InjectMocks
    private TaskExtractionOrchestrator orchestrator;

    private Project project;
    private ProjectTask basicTask;
    private Account staffAccount;

    @BeforeEach
    void setUp() {
        project = new Project();
        project.setId(1L);

        staffAccount = new Account();
        staffAccount.setId(100L);
        staffAccount.setRoles(Set.of(SystemRole.BUSINESS_DEVELOPMENT_STAFF));

        basicTask = new ProjectTask();
        basicTask.setId(10L);
        basicTask.setProject(project);
        basicTask.setTaskType(TaskType.COMPANY_DATA_PREPARATION);
        basicTask.setStatus(TaskStatus.IN_PROGRESS);
        basicTask.setAssignedToAccount(staffAccount);

        lenient().when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        lenient().when(projectTaskRepository.findById(10L)).thenReturn(Optional.of(basicTask));
        lenient().when(projectRepository.existsByIdAndMembersAccountId(1L, 100L)).thenReturn(true);
        lenient().when(accountRepository.findById(100L)).thenReturn(Optional.of(staffAccount));
        lenient().when(jobRepository.save(any(AiExtractionJob.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("Extraction succeeds when raw document belongs to the exact task")
    void startExtractionJob_validTaskDoc_succeeds() {
        RawDocument doc = RawDocument.builder()
                .id("raw-basic-1")
                .projectId("1")
                .taskId("10")
                .isHidden(false)
                .build();
        when(rawDocumentRepository.findById("raw-basic-1")).thenReturn(Optional.of(doc));

        String jobId = orchestrator.startExtractionJob(1L, 10L, List.of("raw-basic-1"), 100L);
        assertNotNull(jobId);
        verify(jobRepository).save(any(AiExtractionJob.class));
    }

    @Test
    @DisplayName("Cross-task document leakage: Task A cannot extract document belonging to Task B (Financial)")
    void startExtractionJob_crossTaskLeakage_financialDoc_rejected() {
        RawDocument financialDoc = RawDocument.builder()
                .id("raw-fin-doc")
                .projectId("1")
                .taskId("20") // Task B
                .isHidden(false)
                .build();
        when(rawDocumentRepository.findById("raw-fin-doc")).thenReturn(Optional.of(financialDoc));

        BusinessValidationException ex = assertThrows(BusinessValidationException.class,
                () -> orchestrator.startExtractionJob(1L, 10L, List.of("raw-fin-doc"), 100L));
        assertTrue(ex.getMessage().contains("RawDocument does not belong to this task"));
        verify(jobRepository, never()).save(any());
    }

    @Test
    @DisplayName("Cross-task document leakage: Task A cannot extract document belonging to Task C (Contract)")
    void startExtractionJob_crossTaskLeakage_contractDoc_rejected() {
        RawDocument contractDoc = RawDocument.builder()
                .id("raw-contract-doc")
                .projectId("1")
                .taskId("30") // Task C
                .isHidden(false)
                .build();
        when(rawDocumentRepository.findById("raw-contract-doc")).thenReturn(Optional.of(contractDoc));

        BusinessValidationException ex = assertThrows(BusinessValidationException.class,
                () -> orchestrator.startExtractionJob(1L, 10L, List.of("raw-contract-doc"), 100L));
        assertTrue(ex.getMessage().contains("RawDocument does not belong to this task"));
        verify(jobRepository, never()).save(any());
    }

    @Test
    @DisplayName("Legacy document with null taskId must be excluded from extraction")
    void startExtractionJob_legacyDocWithoutTaskId_rejected() {
        RawDocument legacyDoc = RawDocument.builder()
                .id("raw-legacy-doc")
                .projectId("1")
                .taskId(null)
                .isHidden(false)
                .build();
        when(rawDocumentRepository.findById("raw-legacy-doc")).thenReturn(Optional.of(legacyDoc));

        BusinessValidationException ex = assertThrows(BusinessValidationException.class,
                () -> orchestrator.startExtractionJob(1L, 10L, List.of("raw-legacy-doc"), 100L));
        assertTrue(ex.getMessage().contains("RawDocument does not belong to this task"));
        verify(jobRepository, never()).save(any());
    }

    @Test
    @DisplayName("Atomic validation: One foreign document in a multi-document request rejects the whole request")
    void startExtractionJob_multiDocumentOneForeign_atomicRejection() {
        RawDocument validDoc = RawDocument.builder()
                .id("raw-valid")
                .projectId("1")
                .taskId("10")
                .isHidden(false)
                .build();
        RawDocument foreignDoc = RawDocument.builder()
                .id("raw-foreign")
                .projectId("1")
                .taskId("20")
                .isHidden(false)
                .build();

        when(rawDocumentRepository.findById("raw-valid")).thenReturn(Optional.of(validDoc));
        when(rawDocumentRepository.findById("raw-foreign")).thenReturn(Optional.of(foreignDoc));

        BusinessValidationException ex = assertThrows(BusinessValidationException.class,
                () -> orchestrator.startExtractionJob(1L, 10L, List.of("raw-valid", "raw-foreign"), 100L));
        assertTrue(ex.getMessage().contains("RawDocument does not belong to this task"));
        verify(jobRepository, never()).save(any());
    }

    @Test
    @DisplayName("Staff not assigned to task cannot trigger AI extraction")
    void startExtractionJob_unassignedStaff_rejected() {
        Account otherStaff = new Account();
        otherStaff.setId(101L);
        otherStaff.setRoles(Set.of(SystemRole.BUSINESS_DEVELOPMENT_STAFF));

        when(projectRepository.existsByIdAndMembersAccountId(1L, 101L)).thenReturn(true);
        when(accountRepository.findById(101L)).thenReturn(Optional.of(otherStaff));

        assertThrows(AccessDeniedException.class,
                () -> orchestrator.startExtractionJob(1L, 10L, List.of("raw-any"), 101L));
        verify(jobRepository, never()).save(any());
    }

    @Test
    @DisplayName("User not member of project cannot trigger AI extraction")
    void startExtractionJob_nonMember_rejected() {
        when(projectRepository.existsByIdAndMembersAccountId(1L, 999L)).thenReturn(false);
        when(projectRepository.existsByIdAndCreatedByAccountId(1L, 999L)).thenReturn(false);

        assertThrows(AccessDeniedException.class,
                () -> orchestrator.startExtractionJob(1L, 10L, List.of("raw-any"), 999L));
        verify(jobRepository, never()).save(any());
    }
}
