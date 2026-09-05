package com.apms.domain.financial;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FinancialMetric {
    private String id;
    private String label;
    private String originalLabel;
    private String metricCode;
    private String normalizedKey;

    private String rawValue;
    private String rawUnit;

    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal normalizedValue;
    private String normalizedUnit;

    private MetricInputMethod inputMethod;
    private ReportingPeriod period;
    private MetricSource source;
    private String evidence;
    private Double confidence;

    private MetricQualityStatus qualityStatus;
    private MetricVerificationStatus verificationStatus;
}
