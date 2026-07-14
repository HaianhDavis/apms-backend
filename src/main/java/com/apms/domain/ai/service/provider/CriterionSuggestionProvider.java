package com.apms.domain.ai.service.provider;

import com.apms.domain.ai.dto.CriterionSuggestionOutput;
import com.apms.domain.score.dto.draft.CompetitorCriterionContext;

public interface CriterionSuggestionProvider {
    CriterionSuggestionOutput generateCriterionSuggestion(CompetitorCriterionContext context, String promptTemplate);
}
