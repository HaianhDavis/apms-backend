package com.apms.domain.score.dto.draft;

import lombok.Builder;
import lombok.Data;
import java.util.Map;

@Data
@Builder
public class BatchGenerationResponse {
    private RoleEvaluationDraftResponse draft;
    private Map<String, String> outcomes;
}
