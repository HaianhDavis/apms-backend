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
    private String fileName;
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

    @Builder.Default
    private FinancialDataEntryMethod dataEntryMethod = FinancialDataEntryMethod.AI_EXTRACTION;

    // Review fields
    private FinancialReportReviewStatus reviewStatus;
    private Long reviewedBy;
    private String reviewedByName;
    private LocalDateTime reviewedAt;
    private String reviewComment;

    public FinancialDataEntryMethod getDataEntryMethod() {
        return this.dataEntryMethod != null ? this.dataEntryMethod : FinancialDataEntryMethod.AI_EXTRACTION;
    }
}
