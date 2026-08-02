package com.apms.domain.ai.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
public class PartnerCriterionSuggestionResponse {
    private String criterionKey;
    private BigDecimal suggestedRawScore;
    private String rationale;
    private List<String> missingDataNotes;
    private BigDecimal confidence;
    private List<String> evidenceReferenceIds;
}
