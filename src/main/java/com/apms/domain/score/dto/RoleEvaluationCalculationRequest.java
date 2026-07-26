package com.apms.domain.score.dto;

import com.apms.domain.company.enums.CompanyRole;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoleEvaluationCalculationRequest {
    private String targetCompanyProfileId;
    private Integer targetProfileVersion;
    private String referenceCompanyProfileId;
    private Integer referenceProfileVersion;
    private CompanyRole evaluatedRole;
    private String ruleSetVersion;
    @Builder.Default
    private LinkedHashMap<String, BigDecimal> criterionScores = new LinkedHashMap<>();
    @Builder.Default
    private Map<String, List<String>> criterionEvidenceRefs = new LinkedHashMap<>();

    private Long calculatedByAccountId;

    // --- Phase 2B Cross-DB Approval Idempotency ---
    private String sourceEvaluationDraftId;
    private String approvalIdempotencyKey;
}
