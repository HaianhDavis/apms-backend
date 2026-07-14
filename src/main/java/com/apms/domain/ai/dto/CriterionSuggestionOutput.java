package com.apms.domain.ai.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
public class CriterionSuggestionOutput {
    private String criterionKey;
    private BigDecimal suggestedRawScore;
    private String explanation;
    private BigDecimal confidence;
    private List<EvidenceReference> evidenceReferences;
    private List<String> missingData;
    private List<String> ambiguities;

    @Data
    public static class EvidenceReference {
        private String evidenceId;
        private String sourceFieldPath;
        private String claim;
    }
}
