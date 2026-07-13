package com.apms.domain.score.draft;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import com.apms.domain.score.enums.CriterionSuggestionMethod;
import com.apms.domain.score.enums.CriterionSuggestionReviewStatus;
import com.apms.domain.score.enums.CriterionSuggestionValidationStatus;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AutomaticSuggestion {

    private String criterionKey;

    private BigDecimal suggestedRawScore;

    @Builder.Default
    private LinkedHashMap<String, BigDecimal> componentScores = new LinkedHashMap<>();

    @Builder.Default
    private LinkedHashMap<String, BigDecimal> componentWeights = new LinkedHashMap<>();

    private BigDecimal componentCoverage;

    @Builder.Default
    private List<String> missingComponents = new ArrayList<>();

    @Builder.Default
    private List<String> calculationWarnings = new ArrayList<>();

    private String suggestionRationale;

    private String rubricVersion;

    private LocalDateTime generatedAt;

    @Builder.Default
    private Boolean accepted = false;

    private Long acceptedByAccountId;
    private LocalDateTime acceptedAt;

    // New Canonical Quality Fields
    private CriterionSuggestionMethod method;
    private String explanation;

    @Builder.Default
    private List<String> evidenceIds = new ArrayList<>();

    @Builder.Default
    private List<String> sourceFieldPaths = new ArrayList<>();

    private BigDecimal confidence;
    private BigDecimal evidenceCoverage;

    private CriterionSuggestionValidationStatus validationStatus;
    private CriterionSuggestionReviewStatus reviewStatus;

    @Builder.Default
    private List<String> validationWarnings = new ArrayList<>();

    @Builder.Default
    private List<String> missingData = new ArrayList<>();

    @Builder.Default
    private LinkedHashMap<String, Object> calculationDetails = new LinkedHashMap<>();

    private String promptVersion;
    private String modelProvider;
    private String modelVersion;

    private Long reviewedByAccountId;
    private LocalDateTime reviewedAt;
    private String reviewComment;

    // Effective fallback helpers

    public String getEffectiveExplanation() {
        return explanation != null ? explanation : suggestionRationale;
    }

    public CriterionSuggestionReviewStatus getEffectiveReviewStatus() {
        if (reviewStatus != null) {
            return reviewStatus;
        }
        if (accepted != null && accepted) {
            return CriterionSuggestionReviewStatus.ACCEPTED;
        }
        return CriterionSuggestionReviewStatus.PENDING;
    }
}
