package com.apms.domain.financial.dto;

import com.apms.domain.financial.ReportingPeriod;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateFinancialMetricRequest {
    private String label;
    private String originalLabel;
    private String metricCode;
    private String statementType;
    private String rawValue;
    private String rawUnit;
    private ReportingPeriod period;
    private String sourceDocumentId;
    private Integer sourcePage;
    private String reportEntryId;
    private String reportId;
    private String evidence;

    public String getReportEntryId() {
        if (reportEntryId != null && !reportEntryId.isBlank()) {
            return reportEntryId;
        }
        return reportId;
    }
}
