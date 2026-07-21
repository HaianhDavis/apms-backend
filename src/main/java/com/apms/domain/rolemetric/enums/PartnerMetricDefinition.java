package com.apms.domain.rolemetric.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;

@Getter
@RequiredArgsConstructor
public enum PartnerMetricDefinition {
    REVENUE_GENERATED(
            "revenue_generated",
            "businessValueContributionScore",
            MetricValueType.CURRENCY,
            true, true,
            MetricPeriodType.PERIOD,
            BigDecimal.ZERO, null
    ),
    COST_SAVINGS(
            "cost_savings",
            "businessValueContributionScore",
            MetricValueType.CURRENCY,
            true, true,
            MetricPeriodType.PERIOD,
            BigDecimal.ZERO, null
    ),
    SLA_UPTIME_PERCENTAGE(
            "sla_uptime_percentage",
            "operationalPerformanceScore",
            MetricValueType.PERCENTAGE,
            true, true,
            MetricPeriodType.PERIOD,
            BigDecimal.ZERO, new BigDecimal("100")
    ),
    DELIVERY_ON_TIME_RATE(
            "delivery_on_time_rate",
            "operationalPerformanceScore",
            MetricValueType.PERCENTAGE,
            true, true,
            MetricPeriodType.PERIOD,
            BigDecimal.ZERO, new BigDecimal("100")
    ),
    NPS_SCORE(
            "nps_score",
            "relationshipQualityScore",
            MetricValueType.DECIMAL,
            true, true,
            MetricPeriodType.POINT_IN_TIME,
            new BigDecimal("-100"), new BigDecimal("100")
    ),
    JOINT_INITIATIVES_COMPLETED(
            "joint_initiatives_completed",
            "relationshipQualityScore",
            MetricValueType.COUNT,
            true, true,
            MetricPeriodType.PERIOD,
            BigDecimal.ZERO, null
    ),
    COMPLIANCE_AUDIT_PASSED(
            "compliance_audit_passed",
            "governanceAndRiskScore",
            MetricValueType.BOOLEAN,
            true, true,
            MetricPeriodType.POINT_IN_TIME,
            null, null
    ),
    SECURITY_INCIDENTS(
            "security_incidents",
            "governanceAndRiskScore",
            MetricValueType.COUNT,
            true, true,
            MetricPeriodType.PERIOD,
            BigDecimal.ZERO, null
    );

    private final String metricKey;
    private final String criterion;
    private final MetricValueType valueType;
    private final boolean targetAllowed;
    private final boolean actualAllowed;
    private final MetricPeriodType periodPolicy;
    private final BigDecimal minValue;
    private final BigDecimal maxValue;

    public static PartnerMetricDefinition fromKey(String key) {
        for (PartnerMetricDefinition def : values()) {
            if (def.getMetricKey().equals(key)) {
                return def;
            }
        }
        throw new com.apms.common.exception.BusinessValidationException("Unknown metric key: " + key);
    }
}
