package com.apms.domain.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExtractionFieldResult {
    private String fieldName;
    private Object value;
    private Object normalizedValue;
    private Double confidence;
    private String evidenceText;
    private java.util.List<String> sourceDocumentIds;
    private Integer pageNumber;

    @Builder.Default
    private ExtractionValidationStatus validationStatus = ExtractionValidationStatus.NOT_CHECKED;
    private String validationMessages;

    @Builder.Default
    private ExtractionReviewStatus reviewStatus = ExtractionReviewStatus.PENDING;
    private Object reviewedValue;
    private Long reviewedByUserId;
    private LocalDateTime reviewedAt;
    private String reviewComment;
}
