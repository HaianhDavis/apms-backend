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
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.user.repository.sql.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
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
    private final AccountRepository accountRepository;
    private final StorageService storageService;

    public record DocumentDownload(Resource resource, String fileName, String mimeType) {}

    // ─────────────────────────────────────────────
    // FILE UPLOAD
    // ─────────────────────────────────────────────

    @Transactional
    public ImportJobResponse uploadDocument(Long projectId, MultipartFile file, Long uploaderUserId) {
        return uploadDocument(projectId, file, uploaderUserId, null);
    }

    @Transactional
    public ImportJobResponse uploadDocument(Long projectId, MultipartFile file, Long uploaderUserId, Long taskId) {
        validateProjectExists(projectId);

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
        RawDocument rawDocument = RawDocument.builder()
                .projectId(String.valueOf(projectId))
                .taskId(taskId != null ? String.valueOf(taskId) : null)
                .importJobId(String.valueOf(importJob.getId()))
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

    @Transactional(readOnly = true)
    public DocumentDownload getDocumentDownload(Long projectId, String rawDocumentId) {
        validateProjectExists(projectId);

        RawDocument rawDocument = rawDocumentRepository.findById(rawDocumentId)
                .orElseThrow(() -> new ResourceNotFoundException("RawDocument not found"));

        if (!String.valueOf(projectId).equals(rawDocument.getProjectId())) {
            throw new ResourceNotFoundException("Document not found in this project");
        }

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

    // ─────────────────────────────────────────────
    // MANUAL INPUT
    // ─────────────────────────────────────────────

    @Transactional
    public ImportJobResponse manualInput(Long projectId, ManualInputRequest request, Long uploaderUserId) {
        validateProjectExists(projectId);

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
        RawDocument rawDocument = RawDocument.builder()
                .projectId(String.valueOf(projectId))
                .importJobId(String.valueOf(importJob.getId()))
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
    public java.util.List<ImportJobResponse> getTaskImportJobs(Long projectId, Long taskId) {
        validateProjectExists(projectId);

        java.util.List<String> rawDocumentIds = rawDocumentRepository
                .findByProjectIdAndTaskIdAndIsHiddenFalse(String.valueOf(projectId), String.valueOf(taskId))
                .stream()
                .map(RawDocument::getId)
                .toList();

        if (rawDocumentIds.isEmpty()) {
            return java.util.List.of();
        }

        return importJobRepository.findByProject_IdAndRawDocumentIdIn(projectId, rawDocumentIds)
                .stream()
                .map(this::toImportJobResponse)
                .toList();
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
