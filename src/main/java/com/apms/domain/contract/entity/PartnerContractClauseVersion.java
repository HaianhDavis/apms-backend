package com.apms.domain.contract.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "partner_contract_clause_versions", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"partner_contract_version_id", "clause_identity"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartnerContractClauseVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "partner_contract_version_id", nullable = false)
    private Long partnerContractVersionId;

    @Column(name = "clause_identity", nullable = false, length = 100)
    private String clauseIdentity;

    @Column(name = "clause_type", nullable = true, length = 100)
    private String clauseType;

    @org.hibernate.annotations.Nationalized
    @Column(name = "clause_title", nullable = true, length = 255)
    private String clauseTitle;

    @Column(name = "effective_date")
    private LocalDate effectiveDate;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Column(name = "notice_period_days")
    private Integer noticePeriodDays;

    @Column(name = "target_metric_key", length = 100)
    private String targetMetricKey;

    @org.hibernate.annotations.Nationalized
    @Column(name = "target_value", length = 255)
    private String targetValue;

    @Column(name = "target_unit", length = 50)
    private String targetUnit;

    @Column(name = "comparator", length = 20)
    private String comparator;

    @Column(name = "measurement_period", length = 50)
    private String measurementPeriod;

    @Column(name = "penalty_value", precision = 18, scale = 2)
    private BigDecimal penaltyValue;

    @Column(name = "penalty_currency", length = 3)
    private String penaltyCurrency;

    @org.hibernate.annotations.Nationalized
    @Column(name = "penalty_description", length = 500)
    private String penaltyDescription;

    @Column(name = "source_raw_document_id", length = 50)
    private String sourceRawDocumentId;

    @org.hibernate.annotations.Nationalized
    @Column(name = "evidence_reference", length = 255)
    private String evidenceReference;

    @org.hibernate.annotations.Nationalized
    @Column(name = "source_excerpt", length = 2000)
    private String sourceExcerpt;

    @Column(name = "clause_hash", length = 128)
    private String clauseHash;

    @Column(name = "approved_by_account_id", nullable = false)
    private Long approvedByAccountId;

    @CreationTimestamp
    @Column(name = "approved_at", nullable = false, updatable = false)
    private LocalDateTime approvedAt;
}
