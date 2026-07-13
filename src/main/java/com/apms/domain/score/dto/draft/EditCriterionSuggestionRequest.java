package com.apms.domain.score.dto.draft;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class EditCriterionSuggestionRequest {
    private BigDecimal rawScore;
    private String overrideReason;
}
