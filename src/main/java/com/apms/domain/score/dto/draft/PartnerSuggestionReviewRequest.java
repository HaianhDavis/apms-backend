package com.apms.domain.score.dto.draft;

import com.apms.domain.score.enums.CriterionSuggestionReviewStatus;
import lombok.Data;

@Data
public class PartnerSuggestionReviewRequest {
    private CriterionSuggestionReviewStatus status;
    private String editedRationale;
}
