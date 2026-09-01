package com.apms.domain.document.service;

import com.apms.common.enums.ImportJobStatus;
import com.apms.common.enums.InputType;
import com.apms.common.exception.BusinessValidationException;
import com.apms.common.exception.ResourceNotFoundException;
import com.apms.domain.document.ImportJob;
import com.apms.domain.document.RawDocument;
import com.apms.domain.document.dto.ImportJobResponse;
import com.apms.domain.document.dto.ManualInputRequest;
import com.apms.domain.document.repository.mongo.CompanyDocumentRepository;
import com.apms.domain.document.repository.mongo.RawDocumentRepository;
import com.apms.domain.document.repository.sql.ImportJobRepository;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.user.repository.sql.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {
    private static final String SOURCE_TYPE_PARTNER_CONTRACT = "PARTNER_CONTRACT";

    private final ImportJobRepository importJobRepository;
    private final RawDocumentRepository rawDocumentRepository;
    private final CompanyDocumentRepository companyDocumentRepository;
    private final ProjectRepository projectRepository;
    private final com.apms.domain.project.repository.sql.ProjectTaskRepository projectTaskRepository;
    private final AccountRepository accountRepository;
    private final com.apms.domain.profile.service.OwnerOrganizationService ownerOrganizationService;
    private final StorageService storageService;
    private final DocumentTextExtractionService documentTextExtractionService;
    private final com.apms.domain.project.service.ProjectTargetProfileResolver projectTargetProfileResolver;

    public record DocumentDownload(Resource resource, String fileName, String mimeType) {}
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
        String extractedText = documentTextExtractionService.extractText(sourceType, localFilePath);
        boolean hasExtractedText = org.springframework.util.StringUtils.hasText(extractedText);

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
                        .inputText(extractedText)
                        .build())
                .storage(RawDocument.Storage.builder()
                        .provider("LOCAL")
                        .path(localFilePath)
                        .mimeType(file.getContentType())
                        .sizeBytes(file.getSize())
                        .build())
                .processing(RawDocument.Processing.builder()
                        .status(hasExtractedText ? "EXTRACTED" : "UPLOADED")
                        .candidateCount(0)
                        .startedAt(now)
                        .completedAt(hasExtractedText ? now : null)
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

    @Transactional(readOnly = true)
    public DocumentDownload getDocumentDownload(Long projectId, String rawDocumentId) {
        validateProjectExists(projectId);

        RawDocument rawDocument = rawDocumentRepository.findById(rawDocumentId)
                .orElseThrow(() -> new ResourceNotFoundException("RawDocument not found"));

        if (!String.valueOf(projectId).equals(rawDocument.getProjectId())) {
            throw new ResourceNotFoundException("Document not found in this project");
        }

        if (isPartnerContractRawDocument(rawDocument)) {
            throw new AccessDeniedException("PARTNER_CONTRACT_DOCUMENT_REQUIRES_TASK_OR_PROFILE_CONTEXT");
        }

        return toDocumentDownload(rawDocument);
    }

    @Transactional(readOnly = true)
    public DocumentDownload getRawDocumentDownload(String rawDocumentId) {
        RawDocument rawDocument = rawDocumentRepository.findById(rawDocumentId)
                .orElseThrow(() -> new ResourceNotFoundException("RawDocument not found"));

        if (isPartnerContractRawDocument(rawDocument)) {
            throw new AccessDeniedException("PARTNER_CONTRACT_DOCUMENT_REQUIRES_TASK_OR_PROFILE_CONTEXT");
        }

        return toDocumentDownload(rawDocument);
    }

    @Transactional(readOnly = true)
    public DocumentDownload getPartnerContractTaskDocumentDownload(Long projectId, Long taskId, String rawDocumentId) {
        ProjectTask task = projectTaskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found with id: " + taskId));

        if (!task.getProject().getId().equals(projectId)) {
            throw new BusinessValidationException("Task does not belong to the specified project");
        }
        if (task.getTaskType() != com.apms.common.enums.TaskType.PARTNER_CONTRACT_COLLECTION) {
            throw new BusinessValidationException("Task is not a PARTNER_CONTRACT_COLLECTION task");
        }

        RawDocument rawDocument = rawDocumentRepository.findById(rawDocumentId)
                .orElseThrow(() -> new ResourceNotFoundException("RawDocument not found"));
        if (!String.valueOf(projectId).equals(rawDocument.getProjectId())
                || !String.valueOf(taskId).equals(rawDocument.getTaskId())) {
            throw new BusinessValidationException("Contract document does not belong to the specified task");
        }
        if (!isPartnerContractRawDocument(rawDocument)) {
            throw new BusinessValidationException("Only partner contract documents can be opened through this endpoint");
        }
        if (Boolean.TRUE.equals(rawDocument.getIsHidden())) {
            throw new ResourceNotFoundException("Contract document is not available");
        }

        return toDocumentDownload(rawDocument);
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

        String targetProfileId = projectTargetProfileResolver.resolveForPartnerContractTask(projectId, taskId, true);

        // Save file locally
        String localFilePath = storageService.store(file);
        // User instruction: document type PARTNER_CONTRACT
        String sourceType = SOURCE_TYPE_PARTNER_CONTRACT;

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

    @Transactional(readOnly = true)
    public Page<ImportJobResponse> getPartnerContractTaskDocuments(Long projectId, Long taskId, boolean includeHidden, Pageable pageable) {
        ProjectTask task = projectTaskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found with id: " + taskId));

        if (!task.getProject().getId().equals(projectId)) {
            throw new BusinessValidationException("Task does not belong to the specified project");
        }
        if (task.getTaskType() != com.apms.common.enums.TaskType.PARTNER_CONTRACT_COLLECTION) {
            throw new BusinessValidationException("Task is not a PARTNER_CONTRACT_COLLECTION task");
        }

        java.util.List<RawDocument> rawDocs = includeHidden
                ? rawDocumentRepository.findByProjectIdAndTaskId(String.valueOf(projectId), String.valueOf(taskId))
                : rawDocumentRepository.findByProjectIdAndTaskIdAndIsHiddenFalse(String.valueOf(projectId), String.valueOf(taskId));

        java.util.List<String> linkedRawDocIds = rawDocs.stream()
                .filter(this::isPartnerContractRawDocument)
                .map(RawDocument::getId)
                .toList();

        if (linkedRawDocIds.isEmpty()) {
            return Page.empty(pageable);
        }

        return importJobRepository.findByProject_IdAndRawDocumentIdIn(projectId, linkedRawDocIds, pageable)
                .map(this::toImportJobResponse);
    }

    @Transactional
    public void deletePartnerContractTaskDocument(Long projectId, Long taskId, String rawDocumentId, Long currentUserId) {
        ProjectTask task = projectTaskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found with id: " + taskId));

        if (!task.getProject().getId().equals(projectId)) {
            throw new BusinessValidationException("Task does not belong to the specified project");
        }
        if (task.getTaskType() != com.apms.common.enums.TaskType.PARTNER_CONTRACT_COLLECTION) {
            throw new BusinessValidationException("Task is not a PARTNER_CONTRACT_COLLECTION task");
        }
        if (task.getStatus() == com.apms.common.enums.TaskStatus.IN_REVIEW
                || task.getStatus() == com.apms.common.enums.TaskStatus.DONE
                || task.getStatus() == com.apms.common.enums.TaskStatus.CANCELLED
                || task.getStatus() == com.apms.common.enums.TaskStatus.BLOCKED) {
            throw new BusinessValidationException("Cannot delete contracts from a package in status: " + task.getStatus());
        }

        com.apms.domain.user.Account currentUser = accountRepository.findById(currentUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));
        boolean isManager = currentUser.getRoles().stream().anyMatch(r -> r == com.apms.common.enums.SystemRole.BUSINESS_DEVELOPMENT_MANAGER);
        boolean isSystemAdmin = currentUser.getRoles().stream().anyMatch(r -> r == com.apms.common.enums.SystemRole.SYSTEM_ADMIN);
        if (!isManager && !isSystemAdmin) {
            if (task.getAssignedToAccount() == null || !task.getAssignedToAccount().getId().equals(currentUserId)) {
                throw new org.springframework.security.access.AccessDeniedException("Staff can only delete contracts from tasks assigned to them");
            }
        }

        RawDocument rawDoc = rawDocumentRepository.findById(rawDocumentId)
                .orElseThrow(() -> new ResourceNotFoundException("RawDocument not found"));
        if (!String.valueOf(projectId).equals(rawDoc.getProjectId()) || !String.valueOf(taskId).equals(rawDoc.getTaskId())) {
            throw new BusinessValidationException("Contract document does not belong to the specified task");
        }
        if (!isPartnerContractRawDocument(rawDoc)) {
            throw new BusinessValidationException("Only partner contract documents can be deleted from this package");
        }

        rawDoc.setIsHidden(true);
        if (rawDoc.getMetadata() != null) {
            rawDoc.getMetadata().setHiddenAt(LocalDateTime.now());
            rawDoc.getMetadata().setUpdatedAt(LocalDateTime.now());
        }
        rawDocumentRepository.save(rawDoc);
        auditLogService.log(currentUserId, com.apms.common.enums.AuditAction.DOCUMENT_DELETED, "RawDocument", rawDocumentId, "Partner contract document removed from package");
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
            java.util.List<String> researchRawDocIds = rawDocumentRepository
                    .findByProjectIdAndSource_TypeNotAndIsHiddenFalse(String.valueOf(projectId), SOURCE_TYPE_PARTNER_CONTRACT)
                    .stream()
                    .map(RawDocument::getId)
                    .toList();

            if (researchRawDocIds.isEmpty()) {
                return Page.empty(pageable);
            }

            return importJobRepository.findByProject_IdAndRawDocumentIdIn(projectId, researchRawDocIds, pageable)
                    .map(this::toImportJobResponse);
        }

        java.util.List<String> researchRawDocIds = rawDocumentRepository
                .findByProjectIdAndSource_TypeNot(String.valueOf(projectId), SOURCE_TYPE_PARTNER_CONTRACT)
                .stream()
                .map(RawDocument::getId)
                .toList();

        if (researchRawDocIds.isEmpty()) {
            return Page.empty(pageable);
        }

        return importJobRepository.findByProject_IdAndRawDocumentIdIn(projectId, researchRawDocIds, pageable)
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
                .filter(rawDoc -> taskId == null || isPartnerContractTask(taskId) || !isPartnerContractRawDocument(rawDoc))
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
        if (rawDoc.getMetadata() == null) {
            rawDoc.setMetadata(new RawDocument.Metadata());
        }
        rawDoc.getMetadata().setUpdatedAt(LocalDateTime.now());
        rawDocumentRepository.save(rawDoc);

        log.info("User {} updated document {} visibility to hidden={}", currentUserId, rawDocumentId, hidden);
    }

    @Transactional
    public void deleteDocument(String rawDocumentId, Long currentUserId) {
        deleteDocument(null, null, rawDocumentId, currentUserId);
    }

    @Transactional
    public void deleteDocument(Long projectId, Long taskId, String rawDocumentId, Long currentUserId) {
        RawDocument rawDoc = rawDocumentRepository.findById(rawDocumentId)
                .orElseThrow(() -> new ResourceNotFoundException("RawDocument not found"));

        if (projectId != null && !String.valueOf(projectId).equals(rawDoc.getProjectId())) {
            throw new BusinessValidationException("Document does not belong to the specified project");
        }

        Long resolvedTaskId = taskId;
        if (resolvedTaskId == null && StringUtils.hasText(rawDoc.getTaskId())) {
            try {
                resolvedTaskId = Long.parseLong(rawDoc.getTaskId());
            } catch (NumberFormatException ignored) {}
        }
        final Long effectiveTaskId = resolvedTaskId;

        if (effectiveTaskId != null) {
            ProjectTask task = projectTaskRepository.findById(effectiveTaskId)
                    .orElseThrow(() -> new ResourceNotFoundException("Task not found with id: " + effectiveTaskId));

            if (task.getStatus() == com.apms.common.enums.TaskStatus.IN_REVIEW ||
                task.getStatus() == com.apms.common.enums.TaskStatus.DONE ||
                task.getStatus() == com.apms.common.enums.TaskStatus.CANCELLED) {
                throw new BusinessValidationException("Cannot delete document while task is in review, completed, or cancelled.");
            }
        }

        rawDoc.setIsHidden(true);
        if (rawDoc.getMetadata() == null) {
            rawDoc.setMetadata(new RawDocument.Metadata());
        }
        rawDoc.getMetadata().setHiddenAt(LocalDateTime.now());
        rawDoc.getMetadata().setUpdatedAt(LocalDateTime.now());
        rawDocumentRepository.save(rawDoc);

        log.info("User {} soft-deleted document {} for project {} task {}", currentUserId, rawDocumentId, projectId, effectiveTaskId);
    }

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

        if (task.getTaskType() != com.apms.common.enums.TaskType.COMPANY_DATA_PREPARATION
                && task.getTaskType() != com.apms.common.enums.TaskType.FINANCIAL_RESEARCH) {
            throw new com.apms.common.exception.BusinessValidationException("Task-linked document uploads are only supported for COMPANY_DATA_PREPARATION or FINANCIAL_RESEARCH via this endpoint.");
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
            case "TXT"  -> "TXT";
            default -> "OTHER";
        };
    }

    private boolean isPartnerContractRawDocument(RawDocument rawDocument) {
        return rawDocument != null
                && rawDocument.getSource() != null
                && SOURCE_TYPE_PARTNER_CONTRACT.equalsIgnoreCase(rawDocument.getSource().getType());
    }

    private DocumentDownload toDocumentDownload(RawDocument rawDocument) {
        RawDocument.Storage storage = rawDocument.getStorage();
        if (storage == null || storage.getPath() == null || !"LOCAL".equalsIgnoreCase(storage.getProvider())) {
            throw new ResourceNotFoundException("No downloadable file is available for this document");
        }

        String fileName = rawDocument.getSource() != null && rawDocument.getSource().getFileName() != null
                ? rawDocument.getSource().getFileName()
                : storage.getPath();

        return new DocumentDownload(
                storageService.loadAsResource(storage.getPath()),
                fileName,
                storage.getMimeType() != null ? storage.getMimeType() : "application/octet-stream"
        );
    }

    private boolean isPartnerContractTask(Long taskId) {
        return taskId != null
                && projectTaskRepository.findById(taskId)
                .map(task -> task.getTaskType() == com.apms.common.enums.TaskType.PARTNER_CONTRACT_COLLECTION)
                .orElse(false);
    }

    private ImportJobResponse toImportJobResponse(ImportJob importJob) {
        RawDocument rawDocument = null;
        if (importJob.getRawDocumentId() != null) {
            rawDocument = rawDocumentRepository.findById(importJob.getRawDocumentId()).orElse(null);
        }
        RawDocument.Storage storage = rawDocument != null ? rawDocument.getStorage() : null;
        RawDocument.Metadata metadata = rawDocument != null ? rawDocument.getMetadata() : null;

        return ImportJobResponse.builder()
                .id(importJob.getId())
                .projectId(importJob.getProjectId())
                .rawDocumentId(importJob.getRawDocumentId())
                .inputType(importJob.getInputType())
                .sourceType(importJob.getSourceType())
                .fileName(importJob.getFileName())
                .status(importJob.getStatus())
                .uploadedBy(importJob.getUploadedById())
                .uploadedByName(importJob.getUploadedByAccount() != null ? importJob.getUploadedByAccount().getEmail() : null)
                .mimeType(storage != null ? storage.getMimeType() : null)
                .fileSizeBytes(storage != null ? storage.getSizeBytes() : null)
                .taskId(rawDocument != null ? rawDocument.getTaskId() : null)
                .uploadedAt(metadata != null ? metadata.getUploadedAt() : importJob.getCreatedAt())
                .startedAt(importJob.getStartedAt())
                .completedAt(importJob.getCompletedAt())
                .errorMessage(importJob.getErrorMessage())
                .createdAt(importJob.getCreatedAt())
                .build();
    }
}
