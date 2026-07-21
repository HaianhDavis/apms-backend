package com.apms.domain.rolemetric.entity;

import com.apms.domain.rolemetric.enums.RoleMetricEvidenceSourceType;
import com.apms.domain.rolemetric.enums.RoleMetricEvidenceValueScope;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "role_metric_evidences")
@Getter
@Setter
@EntityListeners(AuditingEntityListener.class)
public class RoleMetricEvidence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "role_metric_record_id", nullable = false)
    private Long roleMetricRecordId;

    @Enumerated(EnumType.STRING)
    @Column(name = "value_scope", nullable = false, length = 20)
    private RoleMetricEvidenceValueScope valueScope;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 50)
    private RoleMetricEvidenceSourceType sourceType;

    @Column(name = "source_contract_version_id")
    private Long sourceContractVersionId;

    @Column(name = "source_clause_version_id")
    private Long sourceClauseVersionId;

    @Column(name = "source_raw_document_id")
    private String sourceRawDocumentId;

    @Column(name = "document_segment_id")
    private String documentSegmentId;

    @Column(name = "document_hash")
    private String documentHash;

    @Column(name = "source_excerpt", columnDefinition = "NVARCHAR(MAX)")
    private String sourceExcerpt;

    @Column(name = "external_reference", length = 1024)
    private String externalReference;

    @Column(name = "evidence_note", columnDefinition = "NVARCHAR(MAX)")
    private String evidenceNote;

    @Version
    @Column(name = "optimistic_version", nullable = false)
    private Integer optimisticVersion;

    @CreatedBy
    @Column(name = "created_by_account_id", nullable = false, updatable = false)
    private Long createdByAccountId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedBy
    @Column(name = "updated_by_account_id", nullable = false)
    private Long updatedByAccountId;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
