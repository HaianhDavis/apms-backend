package com.apms.domain.score.dto;

import com.apms.domain.company.enums.CompanyRole;
import com.apms.domain.score.enums.EvaluationCompletenessStatus;
import com.apms.domain.score.enums.WeightSource;
import com.apms.domain.score.enums.WeightingMethod;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;

@Data
@Builder
public class RoleEvaluationCalculationResult {
    private CompanyRole evaluatedRole;
    @Builder.Default
    private LinkedHashMap<String, BigDecimal> criterionScores = new LinkedHashMap<>();
    @Builder.Default
    private LinkedHashMap<String, BigDecimal> normalizedCriterionScores = new LinkedHashMap<>();
    @Builder.Default
    private LinkedHashMap<String, BigDecimal> weightsUsed = new LinkedHashMap<>();
    private BigDecimal overallScore;
    private EvaluationCompletenessStatus completenessStatus;
    private List<String> missingCriteria;
    private String ruleSetVersion;
    private String weightVersion;
    private WeightingMethod weightingMethod;
    private WeightSource weightSource;
}
