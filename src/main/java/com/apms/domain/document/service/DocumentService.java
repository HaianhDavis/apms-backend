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
    private final StorageService storageService;

    // ─────────────────────────────────────────────
    // FILE UPLOAD
    // ─────────────────────────────────────────────

    @Transactional
    public ImportJobResponse uploadDocument(Long projectId, MultipartFile file, Long uploaderUserId) {
        validateProjectExists(projectId);

        // Save file locally
        String localFilePath = storageService.store(file);
        String sourceType = deriveSourceType(file.getOriginalFilename());

        // Create ImportJob in SQL
        ImportJob importJob = ImportJob.builder()
                .projectId(projectId)
                .inputType(InputType.FILE_UPLOAD)
                .sourceType(sourceType)
                .fileName(file.getOriginalFilename())
                .localFilePath(localFilePath)
                .uploadedBy(uploaderUserId)
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
    // MANUAL INPUT
    // ─────────────────────────────────────────────

    @Transactional
    public ImportJobResponse manualInput(Long projectId, ManualInputRequest request, Long uploaderUserId) {
        validateProjectExists(projectId);

        // Create ImportJob in SQL
        ImportJob importJob = ImportJob.builder()
                .projectId(projectId)
                .inputType(InputType.MANUAL_INPUT)
                .sourceType("MANUAL")
                .uploadedBy(uploaderUserId)
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
    public Page<ImportJobResponse> getProjectImportJobs(Long projectId, Pageable pageable) {
        validateProjectExists(projectId);
        return importJobRepository.findByProjectId(projectId, pageable)
                .map(this::toImportJobResponse);
    }

    @Transactional(readOnly = true)
    public ImportJobResponse getImportJob(Long jobId) {
        ImportJob importJob = importJobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("ImportJob not found with id: " + jobId));
        return toImportJobResponse(importJob);
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
        return ImportJobResponse.builder()
                .id(importJob.getId())
                .projectId(importJob.getProjectId())
                .rawDocumentId(importJob.getRawDocumentId())
                .inputType(importJob.getInputType())
                .sourceType(importJob.getSourceType())
                .fileName(importJob.getFileName())
                .status(importJob.getStatus())
                .uploadedBy(importJob.getUploadedBy())
                .startedAt(importJob.getStartedAt())
                .completedAt(importJob.getCompletedAt())
                .errorMessage(importJob.getErrorMessage())
                .createdAt(importJob.getCreatedAt())
                .build();
    }
}
