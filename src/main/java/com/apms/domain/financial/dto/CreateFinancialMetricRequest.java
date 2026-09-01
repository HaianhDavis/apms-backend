package com.apms.domain.financial.dto;

import com.apms.domain.financial.ReportingPeriod;
import lombok.Data;

@Data
public class CreateFinancialMetricRequest {
    private String label;
    private String rawValue;
    private String rawUnit;
    private ReportingPeriod period;
    private String sourceDocumentId;
    private Integer sourcePage;
    private String reportEntryId;
    private String evidence;
}
