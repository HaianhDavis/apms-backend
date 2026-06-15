package com.apms.domain.document.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class RawDocumentResponse {
    private String id;
    private String projectId;
    private String importJobId;
    private String inputType;
    private SourceDto source;
    private StorageDto storage;
    private ProcessingDto processing;
    private MetadataDto metadata;

    @Data
    @Builder
    public static class SourceDto {
        private String originalFileName;
        private String contentType;
        private String inputText;
        private String companyNameHint;
    }

    @Data
    @Builder
    public static class StorageDto {
        private String localFilePath;
        private Long fileSizeBytes;
        private LocalDateTime storedAt;
    }

    @Data
    @Builder
    public static class ProcessingDto {
        private String extractedText;
        private String aiRawOutput;
        private LocalDateTime processedAt;
    }

    @Data
    @Builder
    public static class MetadataDto {
        private String uploadedBy;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }
}
