package com.apms.domain.financial.dto;

import com.apms.domain.financial.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FinancialMetricResponse {
    private String id;
    private String label;
    private String originalLabel;
    private String metricCode;
    private String normalizedKey;
    private String rawValue;
    private String rawUnit;
    private String normalizedValue;  // String for BigDecimal precision
    private String normalizedUnit;
    private MetricInputMethod inputMethod;
    private ReportingPeriod period;
    private MetricSource source;
    private String evidence;
    private Double confidence;
    private MetricQualityStatus qualityStatus;
    private MetricVerificationStatus verificationStatus;
}
