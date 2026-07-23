package com.apms.domain.score.dto.draft;

import com.apms.domain.score.enums.EvaluationCompletenessStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoleEvaluationReadinessResponse {
    private String evaluationId;
    private EvaluationCompletenessStatus aggregateCompletenessStatus;
    private Map<String, CriterionReadinessResult> criterionResults;
    private List<String> missingSourceCategories;
    private List<String> blockingReasons;
    private List<String> warnings;
    private boolean staffMaySubmit;
    private LocalDateTime evaluatedAt;
    private String sourceSnapshotHash;
}
