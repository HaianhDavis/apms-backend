package com.apms.domain.document.service;

import com.apms.common.enums.SystemRole;
import com.apms.common.enums.TaskStatus;
import com.apms.common.enums.TaskType;
import com.apms.common.exception.BusinessValidationException;
import com.apms.common.exception.ResourceNotFoundException;
import com.apms.domain.document.ImportJob;
import com.apms.domain.document.RawDocument;
import com.apms.domain.document.dto.ImportJobResponse;
import com.apms.domain.document.dto.ManualInputRequest;
import com.apms.domain.document.repository.mongo.CompanyDocumentRepository;
import com.apms.domain.document.repository.mongo.RawDocumentRepository;
import com.apms.domain.document.repository.sql.ImportJobRepository;
import com.apms.domain.project.Project;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.domain.user.Account;
import com.apms.domain.user.repository.sql.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class DocumentServiceUploadTest {

    @Mock private ProjectRepository projectRepository;
    @Mock private ProjectTaskRepository projectTaskRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private ImportJobRepository importJobRepository;
    @Mock private RawDocumentRepository rawDocumentRepository;
    @Mock private CompanyDocumentRepository companyDocumentRepository;
    @Mock private StorageService storageService;
    @Mock private DocumentTextExtractionService documentTextExtractionService;
    @Mock private com.apms.domain.project.service.ProjectTargetProfileResolver projectTargetProfileResolver;
    @Mock private com.apms.domain.audit.service.AuditLogService auditLogService;

    @InjectMocks
    private DocumentService documentService;

    private Account staffAccount;
    private Project project;
    private ProjectTask task;
    private MockMultipartFile file;
    private ManualInputRequest manualInputRequest;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        
        staffAccount = new Account();
        staffAccount.setId(100L);
        staffAccount.setRoles(Set.of(SystemRole.BUSINESS_DEVELOPMENT_STAFF));

        project = new Project();
        project.setId(1L);

        task = new ProjectTask();
        task.setId(10L);
        task.setProject(project);
        task.setAssignedToAccount(staffAccount);
        task.setTaskType(TaskType.COMPANY_DATA_PREPARATION);
        task.setStatus(TaskStatus.TODO);

        file = new MockMultipartFile("file", "test.pdf", "application/pdf", "dummy content".getBytes());
        
        manualInputRequest = new ManualInputRequest();
        manualInputRequest.setInputText("some manual text");
        manualInputRequest.setCompanyNameHint("some hint");

        when(projectRepository.existsById(1L)).thenReturn(true);
        when(projectRepository.getReferenceById(1L)).thenReturn(project);
        when(projectTaskRepository.findById(10L)).thenReturn(Optional.of(task));
        when(accountRepository.findById(100L)).thenReturn(Optional.of(staffAccount));
        when(accountRepository.getReferenceById(100L)).thenReturn(staffAccount);
        when(storageService.store(any())).thenReturn("path/to/local.pdf");
        when(documentTextExtractionService.extractText(anyString(), anyString())).thenReturn("extracted text");

        when(importJobRepository.save(any(ImportJob.class))).thenAnswer(inv -> {
            ImportJob j = inv.getArgument(0);
            if (j.getId() == null) j.setId(99L);
            return j;
        });
        
        when(rawDocumentRepository.save(any(RawDocument.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ─────────────────────────────────────────────
    // UPLOAD TESTS
    // ─────────────────────────────────────────────

    @Test
    void uploadDocument_validTask_succeeds() {
        ImportJobResponse resp = documentService.uploadDocument(1L, 10L, file, 100L);
        assertNotNull(resp);
        verify(rawDocumentRepository).save(argThat(doc -> "10".equals(doc.getTaskId())));
    }

    @Test
    void uploadDocument_noTaskId_preservesLegacyBehavior() {
        ImportJobResponse resp = documentService.uploadDocument(1L, null, file, 100L);
        assertNotNull(resp);
        verify(rawDocumentRepository).save(argThat(doc -> doc.getTaskId() == null));
    }

    @Test
    void uploadDocument_taskFromAnotherProject_rejected() {
        Project otherProject = new Project();
        otherProject.setId(2L);
        task.setProject(otherProject);

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, 
                () -> documentService.uploadDocument(1L, 10L, file, 100L));
        assertTrue(ex.getMessage().contains("Task does not belong"));
    }

    @Test
    void uploadDocument_unassignedStaff_rejected() {
        Account otherStaff = new Account();
        otherStaff.setId(101L);
        otherStaff.setRoles(Set.of(SystemRole.BUSINESS_DEVELOPMENT_STAFF));
        when(accountRepository.findById(101L)).thenReturn(Optional.of(otherStaff));

        assertThrows(AccessDeniedException.class, 
                () -> documentService.uploadDocument(1L, 10L, file, 101L));
    }

    @Test
    void uploadDocument_partnerContractTask_rejectedByGenericEndpoint() {
        task.setTaskType(TaskType.PARTNER_CONTRACT_COLLECTION);
        assertThrows(BusinessValidationException.class, 
                () -> documentService.uploadDocument(1L, 10L, file, 100L));
    }

    @Test
    void uploadDocument_statusValidation_allowed() {
        task.setStatus(TaskStatus.TODO);
        documentService.uploadDocument(1L, 10L, file, 100L);

        task.setStatus(TaskStatus.IN_PROGRESS);
        documentService.uploadDocument(1L, 10L, file, 100L);
    }

    @Test
    void uploadDocument_statusValidation_rejected() {
        TaskStatus[] rejectedStatuses = {TaskStatus.IN_REVIEW, TaskStatus.BLOCKED, TaskStatus.DONE, TaskStatus.CANCELLED};
        for (TaskStatus status : rejectedStatuses) {
            task.setStatus(status);
            assertThrows(BusinessValidationException.class, () -> documentService.uploadDocument(1L, 10L, file, 100L));
        }
    }

    // ─────────────────────────────────────────────
    // MANUAL INPUT TESTS
    // ─────────────────────────────────────────────

    @Test
    void manualInput_validTask_succeeds() {
        ImportJobResponse resp = documentService.manualInput(1L, 10L, manualInputRequest, 100L);
        assertNotNull(resp);
        verify(rawDocumentRepository).save(argThat(doc -> "10".equals(doc.getTaskId())));
    }

    @Test
    void manualInput_noTaskId_preservesLegacyBehavior() {
        ImportJobResponse resp = documentService.manualInput(1L, null, manualInputRequest, 100L);
        assertNotNull(resp);
        verify(rawDocumentRepository).save(argThat(doc -> doc.getTaskId() == null));
    }

    @Test
    void manualInput_taskFromAnotherProject_rejected() {
        Project otherProject = new Project();
        otherProject.setId(2L);
        task.setProject(otherProject);

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, 
                () -> documentService.manualInput(1L, 10L, manualInputRequest, 100L));
        assertTrue(ex.getMessage().contains("Task does not belong"));
    }

    @Test
    void manualInput_unassignedStaff_rejected() {
        Account otherStaff = new Account();
        otherStaff.setId(101L);
        otherStaff.setRoles(Set.of(SystemRole.BUSINESS_DEVELOPMENT_STAFF));
        when(accountRepository.findById(101L)).thenReturn(Optional.of(otherStaff));

        assertThrows(AccessDeniedException.class, 
                () -> documentService.manualInput(1L, 10L, manualInputRequest, 101L));
    }

    @Test
    void manualInput_partnerContractTask_rejectedByGenericEndpoint() {
        task.setTaskType(TaskType.PARTNER_CONTRACT_COLLECTION);
        assertThrows(BusinessValidationException.class, 
                () -> documentService.manualInput(1L, 10L, manualInputRequest, 100L));
    }

    @Test
    void manualInput_statusValidation_rejected() {
        TaskStatus[] rejectedStatuses = {TaskStatus.IN_REVIEW, TaskStatus.BLOCKED, TaskStatus.DONE, TaskStatus.CANCELLED};
        for (TaskStatus status : rejectedStatuses) {
            task.setStatus(status);
            assertThrows(BusinessValidationException.class, () -> documentService.manualInput(1L, 10L, manualInputRequest, 100L));
        }
    }

    // ─────────────────────────────────────────────
    // WORKBENCH TESTS (getTaskImportJobs)
    // ─────────────────────────────────────────────
    
    @Test
    void getTaskImportJobs_fetchesOnlyExactMatches() {
        when(rawDocumentRepository.findByProjectIdAndTaskIdAndIsHiddenFalse("1", "10"))
                .thenReturn(java.util.List.of(RawDocument.builder().id("r1").build(), RawDocument.builder().id("r2").build()));
        
        when(importJobRepository.findByProject_IdAndRawDocumentIdIn(eq(1L), any(), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        documentService.getTaskImportJobs(1L, 10L, false, org.springframework.data.domain.Pageable.unpaged());

        // Verifies that the correct repository method was called
        verify(rawDocumentRepository).findByProjectIdAndTaskIdAndIsHiddenFalse("1", "10");
        verify(rawDocumentRepository, never()).findByProjectId(anyString());
        verify(rawDocumentRepository, never()).findByProjectIdAndIsHiddenFalse(anyString());
    }
}
