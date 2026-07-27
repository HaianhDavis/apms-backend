package com.apms.domain.rolemetric.dto;

import com.apms.domain.rolemetric.enums.RoleMetricEvidenceSourceType;
import com.apms.domain.rolemetric.enums.RoleMetricEvidenceValueScope;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RoleMetricEvidenceResponse {
    private Long id;
    private Long roleMetricRecordId;
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
    private Integer optimisticVersion;
    private Long createdByAccountId;
    private LocalDateTime createdAt;
    private Long updatedByAccountId;
    private LocalDateTime updatedAt;
}
