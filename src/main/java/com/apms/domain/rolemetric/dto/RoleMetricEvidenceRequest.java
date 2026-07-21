package com.apms.domain.rolemetric.dto;

import com.apms.domain.rolemetric.enums.RoleMetricEvidenceSourceType;
import com.apms.domain.rolemetric.enums.RoleMetricEvidenceValueScope;
import lombok.Data;

@Data
public class RoleMetricEvidenceRequest {
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
}
