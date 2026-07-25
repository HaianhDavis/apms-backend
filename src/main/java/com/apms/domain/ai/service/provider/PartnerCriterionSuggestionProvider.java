package com.apms.domain.ai.service.provider;

import com.apms.domain.score.dto.draft.PartnerCriterionContext;

public interface PartnerCriterionSuggestionProvider {
    /**
     * Generates a JSON string representing the AI's suggestion for a PARTNER criterion.
     */
    String generateSuggestionJson(PartnerCriterionContext context, String promptTemplate);
}
