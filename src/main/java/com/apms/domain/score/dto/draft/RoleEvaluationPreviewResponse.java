package com.apms.domain.score.dto.draft;

import com.apms.domain.score.enums.EvaluationCompletenessStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;

@Data
public class RoleEvaluationPreviewResponse {

    private final String label = "PREVIEW";

    private LinkedHashMap<String, BigDecimal> criterionScores;

    private LinkedHashMap<String, BigDecimal> normalizedCriterionScores;

    private EvaluationCompletenessStatus completenessStatus;

    private List<String> missingCriteria;

    private BigDecimal previewOverallScore;

    private List<String> warnings;
}
