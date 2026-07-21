package com.apms.domain.rolemetric.dto;

import com.apms.domain.rolemetric.enums.RoleMetricEvidenceSourceType;
import com.apms.domain.rolemetric.enums.RoleMetricEvidenceValueScope;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RoleMetricEvidenceVersionResponse {
    private Long id;
    private Long roleMetricRecordVersionId;
    private Long sourceEvidenceId;
    private RoleMetricEvidenceValueScope valueScope;
    private RoleMetricEvidenceSourceType sourceType;
    private Long sourceContractVersionId;
    private Long sourceClauseVersionId;
    private String sourceRawDocumentId;
    private String documentSegmentId;
    private String documentHash;
    private String sourceExcerpt;
    private String externalReference;
    private String evidenceNote;
    private LocalDateTime snapshotAt;
}
