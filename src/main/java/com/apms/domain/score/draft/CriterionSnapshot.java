package com.apms.domain.score.draft;

import com.apms.domain.score.enums.CriterionInputMethod;
import com.apms.domain.score.enums.CriterionSuggestionReviewStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CriterionSnapshot {
    private String criterionKey;
    private BigDecimal rawScore;
    private String evaluationMode;
    private String factualFindings;
    private String finalRationale;
    private CriterionInputMethod inputMethod;
    
    @Builder.Default
    private List<String> evidenceReferenceIds = new ArrayList<>();
    
    private String dataSufficiencyStatus;
    private String missingDataExplanation;
    private CriterionSuggestionReviewStatus suggestionReviewStatus;
    private Boolean staffEdited;
    private String managerFeedback;
    
    @Builder.Default
    private Map<String, Object> acceptedAiMetadata = new HashMap<>();
    
    // AI provider confidence (e.g. 0.0 to 1.0) - NOT a business score
    private BigDecimal aiConfidence;
}
