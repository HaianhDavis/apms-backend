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
    private Boolean provided;

    @Builder.Default
    private ExtractionValidationStatus validationStatus = ExtractionValidationStatus.NOT_CHECKED;
    private String validationMessages;

    @Builder.Default
    private StaffFieldReviewStatus staffReviewStatus = StaffFieldReviewStatus.PENDING;
    private Object staffReviewedValue;
    private Long staffReviewedByUserId;
    private LocalDateTime staffReviewedAt;
    private String staffReviewComment;

    @Builder.Default
    private ExtractionReviewStatus managerReviewStatus = ExtractionReviewStatus.PENDING;
    private Long managerReviewedByUserId;
    private LocalDateTime managerReviewedAt;
    private String managerReviewComment;
    private Integer reviewedRevision;

    private ExtractionReviewStatus previousManagerReviewStatus;
    private String previousManagerReviewComment;
    private Object previousSubmittedValue;
    private Integer previousReviewedRevision;
    private Integer changedInRevision;

    private FieldReviewDecision currentDecision;
    private FieldReviewDecision previousDecision;
    private Integer submittedRound;
    private Boolean resubmittedInCurrentRound;
}
