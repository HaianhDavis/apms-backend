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
public class AiFinancialMetricCandidate {
    private String label;
    private String rawValue;
    private String rawUnit;
    private ReportingPeriod period;
    private Integer sourcePage;
    private String evidence;
    private Double confidence;
}
