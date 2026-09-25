package com.apms.domain.document.dto;

import com.apms.common.enums.ImportJobStatus;
import com.apms.common.enums.InputType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportJobResponse {
    private Long id;
    private Long projectId;
    private String rawDocumentId;
    private InputType inputType;
    private String sourceType;
    private String fileName;
    private ImportJobStatus status;
    private Long uploadedBy;
    private String uploadedByName;
    private String mimeType;
    private Long fileSizeBytes;
    private String taskId;
    private LocalDateTime uploadedAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private String errorMessage;
    private LocalDateTime createdAt;
}
