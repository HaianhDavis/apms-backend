package com.apms.domain.score.dto.draft;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SingleGenerationResponse {
    private RoleEvaluationDraftResponse draft;
    private String outcome;
}
