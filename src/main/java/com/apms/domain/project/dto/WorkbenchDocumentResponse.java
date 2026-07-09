package com.apms.domain.project.dto;

import com.apms.domain.ai.dto.ExtractionQualityStatus;
import com.apms.domain.document.dto.ImportJobResponse;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class WorkbenchDocumentResponse extends ImportJobResponse {
    
    private String latestExtractionId;
    private ExtractionQualityStatus extractionQualityStatus;
    private Double evidenceCoverageRate;
    private Double completenessRate;
    private Integer warningFields;
    private Integer failedFields;
    private boolean canGenerateDraft;

    @Builder(builderMethodName = "workbenchBuilder")
    public WorkbenchDocumentResponse(Long id, Long projectId, String rawDocumentId, com.apms.common.enums.InputType inputType, 
                                     String sourceType, String fileName, com.apms.common.enums.ImportJobStatus status, 
                                     Long uploadedBy, java.time.LocalDateTime startedAt, java.time.LocalDateTime completedAt, 
                                     String errorMessage, java.time.LocalDateTime createdAt,
                                     String latestExtractionId, ExtractionQualityStatus extractionQualityStatus,
                                     Double evidenceCoverageRate, Double completenessRate, 
                                     Integer warningFields, Integer failedFields, boolean canGenerateDraft) {
        super(id, projectId, rawDocumentId, inputType, sourceType, fileName, status, uploadedBy, startedAt, completedAt, errorMessage, createdAt);
        this.latestExtractionId = latestExtractionId;
        this.extractionQualityStatus = extractionQualityStatus;
        this.evidenceCoverageRate = evidenceCoverageRate;
        this.completenessRate = completenessRate;
        this.warningFields = warningFields;
        this.failedFields = failedFields;
        this.canGenerateDraft = canGenerateDraft;
    }
}
