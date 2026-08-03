package com.apms.domain.document.service;

import com.apms.common.enums.ImportJobStatus;
import com.apms.common.enums.InputType;
import com.apms.common.exception.ResourceNotFoundException;
import com.apms.domain.document.ImportJob;
import com.apms.domain.document.RawDocument;
import com.apms.domain.document.dto.ImportJobResponse;
import com.apms.domain.document.dto.ManualInputRequest;
import com.apms.domain.document.repository.mongo.RawDocumentRepository;
import com.apms.domain.document.repository.sql.ImportJobRepository;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.user.repository.sql.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final ImportJobRepository importJobRepository;
    private final RawDocumentRepository rawDocumentRepository;
    private final ProjectRepository projectRepository;
    private final com.apms.domain.project.repository.sql.ProjectTaskRepository projectTaskRepository;
    private final AccountRepository accountRepository;
    private final com.apms.domain.profile.service.OwnerOrganizationService ownerOrganizationService;
    private final StorageService storageService;
    private final com.apms.domain.audit.service.AuditLogService auditLogService;

    // ─────────────────────────────────────────────
    // FILE UPLOAD
    // ─────────────────────────────────────────────

    @Transactional
    public ImportJobResponse uploadDocument(Long projectId, Long taskId, MultipartFile file, Long uploaderUserId) {
        validateProjectExists(projectId);

        if (taskId != null) {
            validateTaskLinkedUpload(projectId, taskId, uploaderUserId);
        }

        // Save file locally
        String localFilePath = storageService.store(file);
        String sourceType = deriveSourceType(file.getOriginalFilename());

        // Create ImportJob in SQL
        ImportJob importJob = ImportJob.builder()
                .project(projectRepository.getReferenceById(projectId))
                .inputType(InputType.FILE_UPLOAD)
                .sourceType(sourceType)
                .fileName(file.getOriginalFilename())
                .localFilePath(localFilePath)
                .uploadedByAccount(accountRepository.getReferenceById(uploaderUserId))
                .startedAt(LocalDateTime.now())
                .status(ImportJobStatus.COMPLETED) // AI not implemented yet
                .completedAt(LocalDateTime.now())
                .build();

        importJob = importJobRepository.save(importJob);

        // Create RawDocument in MongoDB
        LocalDateTime now = LocalDateTime.now();
        RawDocument.RawDocumentBuilder rawDocBuilder = RawDocument.builder()
                .projectId(String.valueOf(projectId))
                .importJobId(String.valueOf(importJob.getId()));

        if (taskId != null) {
            rawDocBuilder.taskId(String.valueOf(taskId));
        }

        RawDocument rawDocument = rawDocBuilder
                .source(RawDocument.Source.builder()
                        .type(sourceType)
                        .fileName(file.getOriginalFilename())
                        .build())
                .storage(RawDocument.Storage.builder()
                        .provider("LOCAL")
                        .path(localFilePath)
                        .mimeType(file.getContentType())
                        .sizeBytes(file.getSize())
                        .build())
                .processing(RawDocument.Processing.builder()
                        .status("UPLOADED")
                        .candidateCount(0)
                        .startedAt(now)
                        .build())
                .metadata(RawDocument.Metadata.builder()
                        .uploadedBy(String.valueOf(uploaderUserId))
                        .uploadedAt(now)
                        .updatedAt(now)
                        .build())
                .build();

        rawDocument = rawDocumentRepository.save(rawDocument);

        // Link ImportJob back to RawDocument
        importJob.setRawDocumentId(rawDocument.getId());
        importJobRepository.save(importJob);

        log.info("Document uploaded: jobId={}, rawDocId={}, project={}", importJob.getId(), rawDocument.getId(), projectId);

        return toImportJobResponse(importJob);
    }

    // ─────────────────────────────────────────────
    // PARTNER CONTRACT UPLOAD
    // ─────────────────────────────────────────────

    @Transactional
    public ImportJobResponse uploadPartnerContractDocument(Long projectId, Long taskId, MultipartFile file, Long uploaderUserId) {
        ProjectTask task = projectTaskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found with id: " + taskId));

        if (!task.getProject().getId().equals(projectId)) {
            throw new com.apms.common.exception.BusinessValidationException("Task does not belong to the specified project");
        }

        if (task.getTaskType() != com.apms.common.enums.TaskType.PARTNER_CONTRACT_COLLECTION) {
            throw new com.apms.common.exception.BusinessValidationException("Task is not a PARTNER_CONTRACT_COLLECTION task");
        }

        if (task.getTargetCompanyProfileId() == null) {
            throw new com.apms.common.exception.BusinessValidationException("Task does not have a target Partner CompanyProfile");
        }

        // Save file locally
        String localFilePath = storageService.store(file);
        // User instruction: document type PARTNER_CONTRACT
        String sourceType = "PARTNER_CONTRACT";

        // Create ImportJob in SQL
        ImportJob importJob = ImportJob.builder()
                .project(task.getProject())
                .inputType(InputType.FILE_UPLOAD)
                .sourceType(sourceType)
                .fileName(file.getOriginalFilename())
                .localFilePath(localFilePath)
                .uploadedByAccount(accountRepository.getReferenceById(uploaderUserId))
                .startedAt(LocalDateTime.now())
                .status(ImportJobStatus.COMPLETED)
                .completedAt(LocalDateTime.now())
                .build();

        importJob = importJobRepository.save(importJob);

        String targetProfileId = task.getTargetCompanyProfileId();
        String ownerProfileId = ownerOrganizationService.getOwnerCompanyId();

        // Create RawDocument in MongoDB
        LocalDateTime now = LocalDateTime.now();
        RawDocument rawDocument = RawDocument.builder()
                .projectId(String.valueOf(projectId))
                .taskId(String.valueOf(taskId))
                .importJobId(String.valueOf(importJob.getId()))
                .targetCompanyProfileId(targetProfileId)
                .ownerCompanyProfileId(ownerProfileId)
                .source(RawDocument.Source.builder()
                        .type(sourceType)
                        .fileName(file.getOriginalFilename())
                        .build())
                .storage(RawDocument.Storage.builder()
                        .provider("LOCAL")
                        .path(localFilePath)
                        .mimeType(file.getContentType())
                        .sizeBytes(file.getSize())
                        .build())
                .processing(RawDocument.Processing.builder()
                        .status("UPLOADED")
                        .candidateCount(0)
                        .startedAt(now)
                        .build())
                .metadata(RawDocument.Metadata.builder()
                        .uploadedBy(String.valueOf(uploaderUserId))
                        .uploadedAt(now)
                        .updatedAt(now)
                        .build())
                .build();

        rawDocument = rawDocumentRepository.save(rawDocument);

        importJob.setRawDocumentId(rawDocument.getId());
        importJobRepository.save(importJob);

        auditLogService.log(uploaderUserId, com.apms.common.enums.AuditAction.UPLOAD_DOCUMENT, "RawDocument", rawDocument.getId(), "Partner Contract document uploaded");

        return toImportJobResponse(importJob);
    }
    // MANUAL INPUT
    // ─────────────────────────────────────────────

    @Transactional
    public ImportJobResponse manualInput(Long projectId, Long taskId, ManualInputRequest request, Long uploaderUserId) {
        validateProjectExists(projectId);

        if (taskId != null) {
            validateTaskLinkedUpload(projectId, taskId, uploaderUserId);
        }

        // Create ImportJob in SQL
        ImportJob importJob = ImportJob.builder()
                .project(projectRepository.getReferenceById(projectId))
                .inputType(InputType.MANUAL_INPUT)
                .sourceType("MANUAL")
                .uploadedByAccount(accountRepository.getReferenceById(uploaderUserId))
                .startedAt(LocalDateTime.now())
                .status(ImportJobStatus.COMPLETED) // AI not implemented yet
                .completedAt(LocalDateTime.now())
                .build();

        importJob = importJobRepository.save(importJob);

        // Create RawDocument in MongoDB
        LocalDateTime now = LocalDateTime.now();
        RawDocument.RawDocumentBuilder rawDocBuilder = RawDocument.builder()
                .projectId(String.valueOf(projectId))
                .importJobId(String.valueOf(importJob.getId()));

        if (taskId != null) {
            rawDocBuilder.taskId(String.valueOf(taskId));
        }

        RawDocument rawDocument = rawDocBuilder
                .source(RawDocument.Source.builder()
                        .type("MANUAL_INPUT")
                        .inputText(request.getInputText())
                        .companyNameHint(request.getCompanyNameHint())
                        .build())
                .storage(RawDocument.Storage.builder()
                        .provider("MANUAL")
                        .build())
                .processing(RawDocument.Processing.builder()
                        .status("EXTRACTED")
                        .candidateCount(0)
                        .startedAt(now)
                        .completedAt(now)
                        .build())
                .metadata(RawDocument.Metadata.builder()
                        .uploadedBy(String.valueOf(uploaderUserId))
                        .uploadedAt(now)
                        .updatedAt(now)
                        .build())
                .build();

        rawDocument = rawDocumentRepository.save(rawDocument);

        // Link ImportJob back to RawDocument
        importJob.setRawDocumentId(rawDocument.getId());
        importJobRepository.save(importJob);

        log.info("Manual input created: jobId={}, rawDocId={}, project={}", importJob.getId(), rawDocument.getId(), projectId);

        return toImportJobResponse(importJob);
    }

    // ─────────────────────────────────────────────
    // READ OPERATIONS
    // ─────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<ImportJobResponse> getProjectImportJobs(Long projectId, boolean includeHidden, Pageable pageable) {
        validateProjectExists(projectId);

        if (!includeHidden) {
            // MVP: fetch all hidden raw documents for this project
            java.util.List<String> hiddenRawDocIds = rawDocumentRepository.findByProjectIdAndIsHiddenTrue(String.valueOf(projectId))
                    .stream()
                    .map(RawDocument::getId)
                    .toList();

            if (!hiddenRawDocIds.isEmpty()) {
                return importJobRepository.findByProject_IdAndRawDocumentIdNotIn(projectId, hiddenRawDocIds, pageable)
                        .map(this::toImportJobResponse);
            }
        }

        return importJobRepository.findByProject_Id(projectId, pageable)
                .map(this::toImportJobResponse);
    }

    @Transactional(readOnly = true)
    public Page<ImportJobResponse> getTaskImportJobs(Long projectId, Long taskId, boolean includeHidden, Pageable pageable) {
        validateProjectExists(projectId);

        java.util.List<RawDocument> rawDocs;
        if (!includeHidden) {
            rawDocs = rawDocumentRepository.findByProjectIdAndTaskIdAndIsHiddenFalse(String.valueOf(projectId), String.valueOf(taskId));
        } else {
            rawDocs = rawDocumentRepository.findByProjectIdAndTaskId(String.valueOf(projectId), String.valueOf(taskId));
        }

        if (rawDocs.isEmpty()) {
            return Page.empty(pageable);
        }

        java.util.List<String> linkedRawDocIds = rawDocs.stream()
                .map(RawDocument::getId)
                .toList();

        return importJobRepository.findByProject_IdAndRawDocumentIdIn(projectId, linkedRawDocIds, pageable)
                .map(this::toImportJobResponse);
    }

    @Transactional(readOnly = true)
    public ImportJobResponse getImportJob(Long jobId) {
        ImportJob importJob = importJobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("ImportJob not found with id: " + jobId));
        return toImportJobResponse(importJob);
    }

    // ─────────────────────────────────────────────
    // WRITE OPERATIONS
    // ─────────────────────────────────────────────

    @Transactional
    public void updateDocumentVisibility(String rawDocumentId, boolean hidden, Long currentUserId) {
        RawDocument rawDoc = rawDocumentRepository.findById(rawDocumentId)
                .orElseThrow(() -> new ResourceNotFoundException("RawDocument not found"));

        rawDoc.setIsHidden(hidden);
        rawDoc.getMetadata().setUpdatedAt(LocalDateTime.now());
        rawDocumentRepository.save(rawDoc);

        // Audit Logging (Assuming AuditLogService is injected, wait I didn't inject it yet. I'll just log to console or inject it)
        log.info("User {} updated document {} visibility to hidden={}", currentUserId, rawDocumentId, hidden);
    }

    @Transactional
    public void deleteDocument(String rawDocumentId, Long currentUserId) {
        RawDocument rawDoc = rawDocumentRepository.findById(rawDocumentId)
                .orElseThrow(() -> new ResourceNotFoundException("RawDocument not found"));

        rawDoc.setIsHidden(true);
        rawDoc.getMetadata().setHiddenAt(LocalDateTime.now());
        rawDoc.getMetadata().setUpdatedAt(LocalDateTime.now());
        rawDocumentRepository.save(rawDoc);

        log.info("User {} soft-deleted document {}", currentUserId, rawDocumentId);
    }

    // ─────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────

    private void validateProjectExists(Long projectId) {
        if (!projectRepository.existsById(projectId)) {
            throw new ResourceNotFoundException("Project not found with id: " + projectId);
        }
    }

    private void validateTaskLinkedUpload(Long projectId, Long taskId, Long uploaderUserId) {
        ProjectTask task = projectTaskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found with id: " + taskId));

        if (!task.getProject().getId().equals(projectId)) {
            throw new com.apms.common.exception.BusinessValidationException("Task does not belong to the specified project");
        }

        if (task.getTaskType() != com.apms.common.enums.TaskType.COMPANY_DATA_PREPARATION) {
            throw new com.apms.common.exception.BusinessValidationException("Task-linked document uploads are only supported for COMPANY_DATA_PREPARATION via this endpoint.");
        }

        com.apms.common.enums.TaskStatus status = task.getStatus();
        if (status == com.apms.common.enums.TaskStatus.DONE ||
            status == com.apms.common.enums.TaskStatus.CANCELLED ||
            status == com.apms.common.enums.TaskStatus.IN_REVIEW ||
            status == com.apms.common.enums.TaskStatus.BLOCKED) {
            throw new com.apms.common.exception.BusinessValidationException("Cannot upload documents to a task in status: " + status);
        }

        com.apms.domain.user.Account uploader = accountRepository.findById(uploaderUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));
        boolean isManager = uploader.getRoles().stream().anyMatch(r -> r == com.apms.common.enums.SystemRole.BUSINESS_DEVELOPMENT_MANAGER);
        boolean isSystemAdmin = uploader.getRoles().stream().anyMatch(r -> r == com.apms.common.enums.SystemRole.SYSTEM_ADMIN);

        if (!isManager && !isSystemAdmin) {
            if (task.getAssignedToAccount() == null || !task.getAssignedToAccount().getId().equals(uploaderUserId)) {
                throw new org.springframework.security.access.AccessDeniedException("Staff can only upload documents to tasks assigned to them");
            }
        }
    }


    private String deriveSourceType(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "OTHER";
        }
        String ext = fileName.substring(fileName.lastIndexOf(".") + 1).toUpperCase();
        return switch (ext) {
            case "PDF"  -> "PDF";
            case "DOC", "DOCX" -> "DOCX";
            case "XLS", "XLSX" -> "XLSX";
            case "CSV"  -> "CSV";
            default -> "OTHER";
        };
    }

    private ImportJobResponse toImportJobResponse(ImportJob importJob) {
        return ImportJobResponse.builder()
                .id(importJob.getId())
                .projectId(importJob.getProjectId())
                .rawDocumentId(importJob.getRawDocumentId())
                .inputType(importJob.getInputType())
                .sourceType(importJob.getSourceType())
                .fileName(importJob.getFileName())
                .status(importJob.getStatus())
                .uploadedBy(importJob.getUploadedById())
                .startedAt(importJob.getStartedAt())
                .completedAt(importJob.getCompletedAt())
                .errorMessage(importJob.getErrorMessage())
                .createdAt(importJob.getCreatedAt())
                .build();
    }
}
