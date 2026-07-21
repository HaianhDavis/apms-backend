package com.apms.domain.rolemetric.entity;

import com.apms.domain.rolemetric.enums.RoleMetricEvidenceSourceType;
import com.apms.domain.rolemetric.enums.RoleMetricEvidenceValueScope;
import lombok.Getter;
import lombok.Setter;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "role_metric_evidence_versions")
@Getter
@Setter
public class RoleMetricEvidenceVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "role_metric_record_version_id", nullable = false)
    private Long roleMetricRecordVersionId;

    @Column(name = "source_evidence_id", nullable = false)
    private Long sourceEvidenceId;

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

    @Column(name = "snapshot_at", nullable = false)
    private LocalDateTime snapshotAt;
}
