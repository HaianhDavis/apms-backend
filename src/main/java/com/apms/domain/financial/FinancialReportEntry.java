package com.apms.domain.financial;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FinancialReportEntry {
    private String id;
    private String documentId;
    private String title;
    private LocalDate publicationDate;
    private ReportingPeriod reportingPeriod;
    private ReportType reportType;
    private StatementScope statementScope;
    private ExtractionStatus extractionStatus;
    private FinancialExtractionStage extractionStage;
    private Integer extractionProgress;
    private LocalDateTime extractionStartedAt;
    private LocalDateTime extractionCompletedAt;
    private String extractionErrorCode;
    private String extractionErrorMessage;
    private DocumentContext documentContext;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Review fields
    @Builder.Default
    private FinancialReportReviewStatus reviewStatus = FinancialReportReviewStatus.PENDING_REVIEW;
    private Long reviewedBy;
    private String reviewedByName;
    private LocalDateTime reviewedAt;
    private String reviewComment;
}
