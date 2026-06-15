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
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final ImportJobRepository importJobRepository;
    private final RawDocumentRepository rawDocumentRepository;
    private final ProjectRepository projectRepository;
    private final StorageService storageService;

    @Transactional
    public ImportJobResponse uploadDocument(Long projectId, MultipartFile file, Long uploaderUserId) {
        validateProjectExists(projectId);

        // Save file locally
        String localFilePath = storageService.store(file);

        // Create ImportJob in SQL
        ImportJob importJob = ImportJob.builder()
                .projectId(projectId)
                .inputType(InputType.FILE_UPLOAD)
                .sourceType(getFileExtension(file.getOriginalFilename()))
                .fileName(file.getOriginalFilename())
                .localFilePath(localFilePath)
                .uploadedBy(uploaderUserId)
                .startedAt(LocalDateTime.now())
                .status(ImportJobStatus.COMPLETED) // AI not implemented yet
                .completedAt(LocalDateTime.now())
                .build();

        importJob = importJobRepository.save(importJob);

        // Create RawDocument in MongoDB
        RawDocument rawDocument = RawDocument.builder()
                .projectId(String.valueOf(projectId))
                .importJobId(String.valueOf(importJob.getId()))
                .inputType(InputType.FILE_UPLOAD.name())
                .source(RawDocument.Source.builder()
                        .originalFileName(file.getOriginalFilename())
                        .contentType(file.getContentType())
                        .build())
                .storage(RawDocument.Storage.builder()
                        .localFilePath(localFilePath)
                        .fileSizeBytes(file.getSize())
                        .storedAt(LocalDateTime.now())
                        .build())
                .metadata(RawDocument.Metadata.builder()
                        .uploadedBy(String.valueOf(uploaderUserId))
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build())
                .build();

        rawDocument = rawDocumentRepository.save(rawDocument);

        // Link ImportJob back to RawDocument
        importJob.setRawDocumentId(rawDocument.getId());
        importJobRepository.save(importJob);

        log.info("Document uploaded: jobId={}, rawDocId={}, project={}", importJob.getId(), rawDocument.getId(), projectId);

        return toImportJobResponse(importJob);
    }

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
        RawDocument rawDocument = RawDocument.builder()
                .projectId(String.valueOf(projectId))
                .importJobId(String.valueOf(importJob.getId()))
                .inputType(InputType.MANUAL_INPUT.name())
                .source(RawDocument.Source.builder()
                        .inputText(request.getInputText())
                        .companyNameHint(request.getCompanyNameHint())
                        .build())
                .metadata(RawDocument.Metadata.builder()
                        .uploadedBy(String.valueOf(uploaderUserId))
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build())
                .build();

        rawDocument = rawDocumentRepository.save(rawDocument);

        // Link ImportJob back to RawDocument
        importJob.setRawDocumentId(rawDocument.getId());
        importJobRepository.save(importJob);

        log.info("Manual input created: jobId={}, rawDocId={}, project={}", importJob.getId(), rawDocument.getId(), projectId);

        return toImportJobResponse(importJob);
    }

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

    private void validateProjectExists(Long projectId) {
        if (!projectRepository.existsById(projectId)) {
            throw new ResourceNotFoundException("Project not found with id: " + projectId);
        }
    }

    private String getFileExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "OTHER";
        }
        return fileName.substring(fileName.lastIndexOf(".") + 1).toUpperCase();
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
