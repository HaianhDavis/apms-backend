package com.apms.domain.rolemetric.dto;

import com.apms.domain.rolemetric.enums.MetricPeriodType;
import com.apms.domain.rolemetric.enums.MetricValueType;
import com.apms.domain.rolemetric.enums.RoleMetricStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class RoleMetricVersionResponse {
    private Long id;
    private Long roleMetricRecordId;
    private Integer versionNumber;
    private Long projectId;
    private Long taskId;
    private String companyId;
    private String relationshipType;
    private String metricKey;
    private MetricValueType valueType;
    private MetricPeriodType periodType;
    private String periodKey;
    private LocalDate measurementDate;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private BigDecimal targetNumericValue;
    private BigDecimal actualNumericValue;
    private Boolean targetBooleanValue;
    private Boolean actualBooleanValue;
    private String unitCode;
    private String currencyCode;
    private Integer workingRevisionNumber;
    private RoleMetricStatus status;
    private Long submittedByAccountId;
    private LocalDateTime submittedAt;
    private Long reviewedByAccountId;
    private LocalDateTime reviewedAt;
    private String reviewComment;
    private LocalDateTime approvedAt;
    private Long approvedByAccountId;

    private List<RoleMetricEvidenceVersionResponse> evidences;
}
