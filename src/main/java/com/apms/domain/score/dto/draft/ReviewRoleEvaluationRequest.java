package com.apms.domain.score.dto.draft;

import com.apms.domain.score.enums.RoleEvaluationReviewDecision;
import lombok.Data;

@Data
public class ReviewRoleEvaluationRequest {
    private RoleEvaluationReviewDecision decision;
    private String comment;
    private Boolean acknowledgeStaleVersions = false;
}
