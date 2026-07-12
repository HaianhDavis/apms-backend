package com.apms.domain.score.dto;

import com.apms.domain.company.enums.CompanyRole;
import com.apms.domain.score.enums.EvaluationCompletenessStatus;
import com.apms.domain.score.enums.WeightSource;
import com.apms.domain.score.enums.WeightingMethod;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;

@Data
@Builder
public class RoleScoreSnapshotResponse {
    private Long id;
    private String targetCompanyProfileId;
    private Integer targetProfileVersion;
    private String referenceCompanyProfileId;
    private Integer referenceProfileVersion;
    private CompanyRole evaluatedRole;
    private LinkedHashMap<String, BigDecimal> criterionScores;
    private LinkedHashMap<String, BigDecimal> normalizedCriterionScores;
    private LinkedHashMap<String, BigDecimal> weightsUsed;
    private BigDecimal overallScore;
    private EvaluationCompletenessStatus completenessStatus;
    private List<String> missingCriteria;
    private String scoreRuleSetVersion;
    private String weightVersion;
    private WeightingMethod weightingMethod;
    private WeightSource weightSource;
    private Instant calculatedAt;
}
