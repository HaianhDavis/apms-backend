//package com.apms.domain.document.service;
//
//import com.apms.common.enums.SystemRole;
//import com.apms.common.enums.TaskStatus;
//import com.apms.common.enums.TaskType;
//import com.apms.common.exception.BusinessValidationException;
//import com.apms.common.exception.ResourceNotFoundException;
//import com.apms.domain.document.ImportJob;
//import com.apms.domain.document.RawDocument;
//import com.apms.domain.document.dto.ImportJobResponse;
//import com.apms.domain.document.dto.ManualInputRequest;
//import com.apms.domain.document.repository.mongo.CompanyDocumentRepository;
//import com.apms.domain.document.repository.mongo.RawDocumentRepository;
//import com.apms.domain.document.repository.sql.ImportJobRepository;
//import com.apms.domain.project.Project;
//import com.apms.domain.project.ProjectTask;
//import com.apms.domain.project.repository.sql.ProjectRepository;
//import com.apms.domain.project.repository.sql.ProjectTaskRepository;
//import com.apms.domain.user.Account;
//import com.apms.domain.user.repository.sql.AccountRepository;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.mockito.InjectMocks;
//import org.mockito.Mock;
//import org.mockito.MockitoAnnotations;
//import org.springframework.mock.web.MockMultipartFile;
//import org.springframework.security.access.AccessDeniedException;
//
//import java.util.Optional;
//import java.util.Set;
//
//import static org.junit.jupiter.api.Assertions.*;
//import static org.mockito.ArgumentMatchers.any;
//import static org.mockito.Mockito.*;
//
//public class DocumentServiceUploadTest {
//
//    @Mock private ProjectRepository projectRepository;
//    @Mock private ProjectTaskRepository projectTaskRepository;
//    @Mock private AccountRepository accountRepository;
//    @Mock private ImportJobRepository importJobRepository;
//    @Mock private RawDocumentRepository rawDocumentRepository;
//    @Mock private CompanyDocumentRepository companyDocumentRepository;
//    @Mock private StorageService storageService;
//    @Mock private DocumentTextExtractionService documentTextExtractionService;
//    @Mock private com.apms.domain.project.service.ProjectTargetProfileResolver projectTargetProfileResolver;
//    @Mock private com.apms.domain.audit.service.AuditLogService auditLogService;
//    @Mock private com.apms.domain.profile.service.OwnerOrganizationService ownerOrganizationService;
//
//    @InjectMocks
//    private DocumentService documentService;
//
//    private Account staffAccount;
//    private Project project;
//    private ProjectTask task;
//    private MockMultipartFile file;
//    private ManualInputRequest manualInputRequest;
//
//    @BeforeEach
//    void setUp() {
//        MockitoAnnotations.openMocks(this);
//
//        staffAccount = new Account();
//        staffAccount.setId(100L);
//        staffAccount.setRoles(Set.of(SystemRole.BUSINESS_DEVELOPMENT_STAFF));
//
//        project = new Project();
//        project.setId(1L);
//
//        task = new ProjectTask();
//        task.setId(10L);
//        task.setProject(project);
//        task.setAssignedToAccount(staffAccount);
//        task.setTaskType(TaskType.COMPANY_DATA_PREPARATION);
//        task.setStatus(TaskStatus.TODO);
//
//        file = new MockMultipartFile("file", "test.pdf", "application/pdf", "dummy content".getBytes());
//
//        manualInputRequest = new ManualInputRequest();
//        manualInputRequest.setInputText("some manual text");
//        manualInputRequest.setCompanyNameHint("some hint");
//
//        when(projectRepository.existsById(1L)).thenReturn(true);
//        when(projectRepository.getReferenceById(1L)).thenReturn(project);
//        when(projectTaskRepository.findById(10L)).thenReturn(Optional.of(task));
//        when(accountRepository.findById(100L)).thenReturn(Optional.of(staffAccount));
//        when(accountRepository.getReferenceById(100L)).thenReturn(staffAccount);
//        when(storageService.store(any())).thenReturn("path/to/local.pdf");
//        when(documentTextExtractionService.extractText(anyString(), anyString())).thenReturn("extracted text");
//
//        when(importJobRepository.save(any(ImportJob.class))).thenAnswer(inv -> {
//            ImportJob j = inv.getArgument(0);
//            if (j.getId() == null) j.setId(99L);
//            return j;
//        });
//
//        when(rawDocumentRepository.save(any(RawDocument.class))).thenAnswer(inv -> inv.getArgument(0));
//    }
//
//    // ─────────────────────────────────────────────
//    // UPLOAD TESTS
//    // ─────────────────────────────────────────────
//
//    @Test
//    void uploadDocument_validTask_succeeds() {
//        ImportJobResponse resp = documentService.uploadDocument(1L, 10L, file, 100L);
//        assertNotNull(resp);
//        verify(rawDocumentRepository).save(argThat(doc -> "10".equals(doc.getTaskId())));
//    }
//
//    @Test
//    void uploadDocument_noTaskId_preservesLegacyBehavior() {
//        ImportJobResponse resp = documentService.uploadDocument(1L, null, file, 100L);
//        assertNotNull(resp);
//        verify(rawDocumentRepository).save(argThat(doc -> doc.getTaskId() == null));
//    }
//
//    @Test
//    void uploadDocument_taskFromAnotherProject_rejected() {
//        Project otherProject = new Project();
//        otherProject.setId(2L);
//        task.setProject(otherProject);
//
//        BusinessValidationException ex = assertThrows(BusinessValidationException.class,
//                () -> documentService.uploadDocument(1L, 10L, file, 100L));
//        assertTrue(ex.getMessage().contains("Task does not belong"));
//    }
//
//    @Test
//    void uploadDocument_unassignedStaff_rejected() {
//        Account otherStaff = new Account();
//        otherStaff.setId(101L);
//        otherStaff.setRoles(Set.of(SystemRole.BUSINESS_DEVELOPMENT_STAFF));
//        when(accountRepository.findById(101L)).thenReturn(Optional.of(otherStaff));
//
//        assertThrows(AccessDeniedException.class,
//                () -> documentService.uploadDocument(1L, 10L, file, 101L));
//    }
//
//    @Test
//    void uploadDocument_partnerContractTask_rejectedByGenericEndpoint() {
//        task.setTaskType(TaskType.PARTNER_CONTRACT_COLLECTION);
//        assertThrows(BusinessValidationException.class,
//                () -> documentService.uploadDocument(1L, 10L, file, 100L));
//    }
//
//    @Test
//    void uploadDocument_statusValidation_allowed() {
//        task.setStatus(TaskStatus.TODO);
//        documentService.uploadDocument(1L, 10L, file, 100L);
//
//        task.setStatus(TaskStatus.IN_PROGRESS);
//        documentService.uploadDocument(1L, 10L, file, 100L);
//    }
//
//    @Test
//    void uploadDocument_statusValidation_rejected() {
//        TaskStatus[] rejectedStatuses = {TaskStatus.IN_REVIEW, TaskStatus.BLOCKED, TaskStatus.DONE, TaskStatus.CANCELLED};
//        for (TaskStatus status : rejectedStatuses) {
//            task.setStatus(status);
//            assertThrows(BusinessValidationException.class, () -> documentService.uploadDocument(1L, 10L, file, 100L));
//        }
//    }
//
//    // ─────────────────────────────────────────────
//    // MANUAL INPUT TESTS
//    // ─────────────────────────────────────────────
//
//    @Test
//    void manualInput_validTask_succeeds() {
//        ImportJobResponse resp = documentService.manualInput(1L, 10L, manualInputRequest, 100L);
//        assertNotNull(resp);
//        verify(rawDocumentRepository).save(argThat(doc -> "10".equals(doc.getTaskId())));
//    }
//
//    @Test
//    void manualInput_noTaskId_preservesLegacyBehavior() {
//        ImportJobResponse resp = documentService.manualInput(1L, null, manualInputRequest, 100L);
//        assertNotNull(resp);
//        verify(rawDocumentRepository).save(argThat(doc -> doc.getTaskId() == null));
//    }
//
//    @Test
//    void manualInput_taskFromAnotherProject_rejected() {
//        Project otherProject = new Project();
//        otherProject.setId(2L);
//        task.setProject(otherProject);
//
//        BusinessValidationException ex = assertThrows(BusinessValidationException.class,
//                () -> documentService.manualInput(1L, 10L, manualInputRequest, 100L));
//        assertTrue(ex.getMessage().contains("Task does not belong"));
//    }
//
//    @Test
//    void manualInput_unassignedStaff_rejected() {
//        Account otherStaff = new Account();
//        otherStaff.setId(101L);
//        otherStaff.setRoles(Set.of(SystemRole.BUSINESS_DEVELOPMENT_STAFF));
//        when(accountRepository.findById(101L)).thenReturn(Optional.of(otherStaff));
//
//        assertThrows(AccessDeniedException.class,
//                () -> documentService.manualInput(1L, 10L, manualInputRequest, 101L));
//    }
//
//    @Test
//    void manualInput_partnerContractTask_rejectedByGenericEndpoint() {
//        task.setTaskType(TaskType.PARTNER_CONTRACT_COLLECTION);
//        assertThrows(BusinessValidationException.class,
//                () -> documentService.manualInput(1L, 10L, manualInputRequest, 100L));
//    }
//
//    @Test
//    void manualInput_statusValidation_rejected() {
//        TaskStatus[] rejectedStatuses = {TaskStatus.IN_REVIEW, TaskStatus.BLOCKED, TaskStatus.DONE, TaskStatus.CANCELLED};
//        for (TaskStatus status : rejectedStatuses) {
//            task.setStatus(status);
//            assertThrows(BusinessValidationException.class, () -> documentService.manualInput(1L, 10L, manualInputRequest, 100L));
//        }
//    }
//
//    // ─────────────────────────────────────────────
//    // WORKBENCH TESTS (getTaskImportJobs)
//    // ─────────────────────────────────────────────
//
//    @Test
//    void getTaskImportJobs_fetchesOnlyExactMatches() {
//        when(rawDocumentRepository.findByProjectIdAndTaskIdAndIsHiddenFalse("1", "10"))
//                .thenReturn(java.util.List.of(RawDocument.builder().id("r1").build(), RawDocument.builder().id("r2").build()));
//
//        when(importJobRepository.findByProject_IdAndRawDocumentIdIn(eq(1L), any(), any()))
//                .thenReturn(org.springframework.data.domain.Page.empty());
//
//        documentService.getTaskImportJobs(1L, 10L, false, org.springframework.data.domain.Pageable.unpaged());
//
//        // Verifies that the correct repository method was called
//        verify(rawDocumentRepository).findByProjectIdAndTaskIdAndIsHiddenFalse("1", "10");
//        verify(rawDocumentRepository, never()).findByProjectId(anyString());
//        verify(rawDocumentRepository, never()).findByProjectIdAndIsHiddenFalse(anyString());
//    }
//
//    @Test
//    void getTaskImportJobs_preservesIncludeHidden_false() {
//        when(rawDocumentRepository.findByProjectIdAndTaskIdAndIsHiddenFalse("1", "10"))
//                .thenReturn(java.util.List.of(RawDocument.builder().id("r1").build()));
//        when(importJobRepository.findByProject_IdAndRawDocumentIdIn(eq(1L), any(), any()))
//                .thenReturn(org.springframework.data.domain.Page.empty());
//
//        documentService.getTaskImportJobs(1L, 10L, false, org.springframework.data.domain.Pageable.unpaged());
//
//        verify(rawDocumentRepository).findByProjectIdAndTaskIdAndIsHiddenFalse("1", "10");
//        verify(rawDocumentRepository, never()).findByProjectIdAndTaskId("1", "10");
//    }
//
//    @Test
//    void getTaskImportJobs_preservesIncludeHidden_true() {
//        when(rawDocumentRepository.findByProjectIdAndTaskId("1", "10"))
//                .thenReturn(java.util.List.of(RawDocument.builder().id("r1").build()));
//        when(importJobRepository.findByProject_IdAndRawDocumentIdIn(eq(1L), any(), any()))
//                .thenReturn(org.springframework.data.domain.Page.empty());
//
//        documentService.getTaskImportJobs(1L, 10L, true, org.springframework.data.domain.Pageable.unpaged());
//
//        verify(rawDocumentRepository).findByProjectIdAndTaskId("1", "10");
//        verify(rawDocumentRepository, never()).findByProjectIdAndTaskIdAndIsHiddenFalse("1", "10");
//    }
//
//    @Test
//    void getTaskImportJobs_taskFromDifferentProject_rejected() {
//        Project otherProject = new Project();
//        otherProject.setId(99L);
//        task.setProject(otherProject);
//
//        assertThrows(BusinessValidationException.class,
//                () -> documentService.getTaskImportJobs(1L, 10L, false, org.springframework.data.domain.Pageable.unpaged()));
//    }
//
//    // ─────────────────────────────────────────────
//    // TASK-SCOPED DOWNLOAD TESTS
//    // ─────────────────────────────────────────────
//
//    @Test
//    void getTaskDocumentDownload_success_forTaskDoc() {
//        RawDocument doc = RawDocument.builder()
//                .id("raw-doc-1")
//                .projectId("1")
//                .taskId("10")
//                .storage(RawDocument.Storage.builder().provider("LOCAL").path("path/test.pdf").mimeType("application/pdf").build())
//                .source(RawDocument.Source.builder().fileName("test.pdf").build())
//                .build();
//        when(rawDocumentRepository.findById("raw-doc-1")).thenReturn(Optional.of(doc));
//        when(storageService.loadAsResource("path/test.pdf")).thenReturn(mock(org.springframework.core.io.Resource.class));
//
//        DocumentService.DocumentDownload download = documentService.getTaskDocumentDownload(1L, 10L, "raw-doc-1");
//        assertNotNull(download);
//        assertEquals("test.pdf", download.fileName());
//    }
//
//    @Test
//    void getTaskDocumentDownload_crossTaskLeakage_rejected() {
//        // Document belongs to Task 20 (e.g. Financial), requested via Task 10 (Basic Company)
//        RawDocument financialDoc = RawDocument.builder()
//                .id("fin-doc")
//                .projectId("1")
//                .taskId("20")
//                .build();
//        when(rawDocumentRepository.findById("fin-doc")).thenReturn(Optional.of(financialDoc));
//
//        BusinessValidationException ex = assertThrows(BusinessValidationException.class,
//                () -> documentService.getTaskDocumentDownload(1L, 10L, "fin-doc"));
//        assertTrue(ex.getMessage().contains("Document does not belong to the specified task"));
//    }
//
//    @Test
//    void getTaskDocumentDownload_legacyDocWithoutTaskId_rejected() {
//        // Legacy document with taskId == null must be excluded from task download
//        RawDocument legacyDoc = RawDocument.builder()
//                .id("legacy-doc")
//                .projectId("1")
//                .taskId(null)
//                .build();
//        when(rawDocumentRepository.findById("legacy-doc")).thenReturn(Optional.of(legacyDoc));
//
//        BusinessValidationException ex = assertThrows(BusinessValidationException.class,
//                () -> documentService.getTaskDocumentDownload(1L, 10L, "legacy-doc"));
//        assertTrue(ex.getMessage().contains("Document does not belong to the specified task"));
//    }
//
//    @Test
//    void getTaskDocumentDownload_projectMismatch_rejected() {
//        RawDocument doc = RawDocument.builder()
//                .id("raw-doc-other-project")
//                .projectId("2")
//                .taskId("10")
//                .build();
//        when(rawDocumentRepository.findById("raw-doc-other-project")).thenReturn(Optional.of(doc));
//
//        assertThrows(BusinessValidationException.class,
//                () -> documentService.getTaskDocumentDownload(1L, 10L, "raw-doc-other-project"));
//    }
//
//    // ─────────────────────────────────────────────
//    // TASK-SCOPED DELETE TESTS
//    // ─────────────────────────────────────────────
//
//    @Test
//    void deleteDocument_withTaskId_success_forTaskDoc() {
//        RawDocument doc = RawDocument.builder()
//                .id("raw-doc-1")
//                .projectId("1")
//                .taskId("10")
//                .isHidden(false)
//                .build();
//        when(rawDocumentRepository.findById("raw-doc-1")).thenReturn(Optional.of(doc));
//
//        documentService.deleteDocument(1L, 10L, "raw-doc-1", 100L);
//
//        assertTrue(doc.getIsHidden());
//        verify(rawDocumentRepository).save(doc);
//    }
//
//    @Test
//    void deleteDocument_withTaskId_crossTaskLeakage_rejected() {
//        // Doc belongs to Task 20, tried to delete through Task 10
//        RawDocument doc = RawDocument.builder()
//                .id("doc-task-20")
//                .projectId("1")
//                .taskId("20")
//                .build();
//        when(rawDocumentRepository.findById("doc-task-20")).thenReturn(Optional.of(doc));
//
//        BusinessValidationException ex = assertThrows(BusinessValidationException.class,
//                () -> documentService.deleteDocument(1L, 10L, "doc-task-20", 100L));
//        assertTrue(ex.getMessage().contains("Document does not belong to the specified task"));
//        verify(rawDocumentRepository, never()).save(any());
//    }
//
//    @Test
//    void deleteDocument_withTaskId_unassignedStaff_rejected() {
//        Account otherStaff = new Account();
//        otherStaff.setId(101L);
//        otherStaff.setRoles(Set.of(SystemRole.BUSINESS_DEVELOPMENT_STAFF));
//        when(accountRepository.findById(101L)).thenReturn(Optional.of(otherStaff));
//
//        assertThrows(AccessDeniedException.class,
//                () -> documentService.deleteDocument(1L, 10L, "any-doc", 101L));
//    }
//
//    @Test
//    void deleteDocument_withTaskId_inReviewStatus_rejected() {
//        task.setStatus(TaskStatus.IN_REVIEW);
//
//        assertThrows(BusinessValidationException.class,
//                () -> documentService.deleteDocument(1L, 10L, "any-doc", 100L));
//    }
//}
//    @Test
//    void partnerContractUploadLinksProjectTaskAndRawDocument() {
//        task.setTaskType(TaskType.PARTNER_CONTRACT_COLLECTION);
//        when(rawDocumentRepository.save(any(RawDocument.class))).thenAnswer(inv -> {
//            RawDocument doc = inv.getArgument(0);
//            doc.setId("contract-pdf");
//            return doc;
//        });
//        ImportJobResponse response = documentService.uploadPartnerContractDocument(1L, 10L, file, 100L);
//        assertEquals("contract-pdf", response.getRawDocumentId());
//        verify(rawDocumentRepository).save(argThat(doc -> "1".equals(doc.getProjectId())
//                && "10".equals(doc.getTaskId()) && "test.pdf".equals(doc.getSource().getFileName())));
//    }
//
//    @Test
//    void contractUploadRejectsUnrelatedTaskAndUnassignedStaff() {
//        assertThrows(BusinessValidationException.class,
//                () -> documentService.uploadPartnerContractDocument(1L, 10L, file, 100L));
//        task.setTaskType(TaskType.PARTNER_CONTRACT_COLLECTION);
//        task.setAssignedToAccount(null);
//        assertThrows(AccessDeniedException.class,
//                () -> documentService.uploadPartnerContractDocument(1L, 10L, file, 100L));
//        verify(storageService, never()).store(any());
//    }
//
//    @Test
//    void contractUploadRejectsEmptyPdfAndGenericEndpointStillRejectsContract() {
//        task.setTaskType(TaskType.PARTNER_CONTRACT_COLLECTION);
//        var empty = new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0]);
//        assertThrows(BusinessValidationException.class,
//                () -> documentService.uploadPartnerContractDocument(1L, 10L, empty, 100L));
//        assertThrows(BusinessValidationException.class,
//                () -> documentService.uploadDocument(1L, 10L, file, 100L));
//        verify(storageService, never()).store(any());
//    }
//
//    @Test
//    void financialUploadStillUsesGenericEndpoint() {
//        task.setTaskType(TaskType.FINANCIAL_RESEARCH);
//        assertNotNull(documentService.uploadDocument(1L, 10L, file, 100L));
//    }
