package com.apms.domain.rolemetric.entity;

import com.apms.domain.rolemetric.enums.MetricPeriodType;
import com.apms.domain.rolemetric.enums.MetricValueType;
import com.apms.domain.rolemetric.enums.RoleMetricStatus;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "role_metric_records", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"project_id", "company_id", "relationship_type", "metric_key", "period_key"})
})
@Getter
@Setter
public class RoleMetricRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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

    @Column(name = "period_key", nullable = false, length = 100)
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

    @Column(name = "current_approved_version_id")
    private Long currentApprovedVersionId;

    @Column(name = "current_approved_version_number")
    private Integer currentApprovedVersionNumber;

    @Column(name = "working_revision_number", nullable = false)
    private Integer workingRevisionNumber;

    @Version
    @Column(name = "optimistic_version", nullable = false)
    private Integer optimisticVersion;

    @Column(name = "created_by_account_id", nullable = false, updatable = false)
    private Long createdByAccountId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
