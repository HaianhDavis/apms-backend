package com.apms.domain.rolemetric.entity;

import com.apms.domain.rolemetric.enums.MetricPeriodType;
import com.apms.domain.rolemetric.enums.MetricValueType;
import com.apms.domain.rolemetric.enums.RoleMetricStatus;
import lombok.Getter;
import lombok.Setter;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "role_metric_record_versions", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"role_metric_record_id", "version_number"})
})
@Getter
@Setter
public class RoleMetricRecordVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "role_metric_record_id", nullable = false)
    private Long roleMetricRecordId;

    @Column(name = "version_number", nullable = false)
    private Integer versionNumber;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "task_id")
    private Long taskId;

    @Column(name = "company_id", nullable = false)
    private String companyId;

    @Column(name = "relationship_type", nullable = false)
    private String relationshipType;

    @Column(name = "metric_key", nullable = false)
    private String metricKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "period_type", nullable = false)
    private MetricPeriodType periodType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private MetricValueType valueType;

    @Column(name = "period_key", nullable = false)
    private String periodKey;

    @Column(name = "measurement_date")
    private LocalDate measurementDate;

    @Column(name = "period_start")
    private LocalDate periodStart;

    @Column(name = "period_end")
    private LocalDate periodEnd;

    @Column(name = "target_numeric_value", precision = 19, scale = 4)
    private BigDecimal targetNumericValue;

    @Column(name = "actual_numeric_value", precision = 19, scale = 4)
    private BigDecimal actualNumericValue;

    @Column(name = "target_boolean_value")
    private Boolean targetBooleanValue;

    @Column(name = "actual_boolean_value")
    private Boolean actualBooleanValue;

    @Column(name = "unit_code", length = 50)
    private String unitCode;

    @Column(name = "currency_code", length = 3)
    private String currencyCode;

    @Column(name = "working_revision_number", nullable = false)
    private Integer workingRevisionNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private RoleMetricStatus status;

    @Column(name = "submitted_by_account_id")
    private Long submittedByAccountId;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "reviewed_by_account_id")
    private Long reviewedByAccountId;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "review_comment", columnDefinition = "NVARCHAR(MAX)")
    private String reviewComment;

    @Column(name = "approved_at", nullable = false)
    private LocalDateTime approvedAt;

    @Column(name = "approved_by_account_id", nullable = false)
    private Long approvedByAccountId;
}
