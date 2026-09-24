package com.apms.domain.financial.dto;

import com.apms.domain.financial.FinancialResearchStatus;
import com.apms.domain.financial.FinancialReportEntry;
import com.apms.domain.financial.ReportingPeriod;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FinancialResearchResponse {
    private String id;
    private Long taskId;
    private Long projectId;
    private String companyProfileId;
    private ReportingPeriod targetResearchPeriod;
    private List<FinancialReportEntry> reports;
    private List<FinancialMetricResponse> metrics;
    private List<String> submittedReportIds;
    private FinancialResearchStatus status;
    private LocalDateTime submittedAt;
    private Long reviewedBy;
    private LocalDateTime reviewedAt;
    private String reviewReason;
    private Boolean canRecallSubmission;
    private Long activeSubmissionId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
