package com.apms.domain.score.service;

import com.apms.domain.score.draft.AutomaticSuggestion;
import com.apms.domain.score.draft.RoleEvaluationDraft;
import com.apms.domain.score.enums.CriterionSuggestionReviewStatus;
import com.apms.domain.score.enums.CriterionSuggestionValidationStatus;
import com.apms.domain.score.registry.CanonicalRoleCriteria;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class CriterionSuggestionValidator {

    private static final Pattern SOURCE_PATH_PATTERN = Pattern.compile("^(reference|target|relationship|contract|metric|external)(\\.[a-zA-Z0-9_]+)*$");

    public void validate(String criterionKey, AutomaticSuggestion suggestion, RoleEvaluationDraft draft) {

        // 1. Unknown criterion rejection
        if (!CanonicalRoleCriteria.isValidCriterionForRole(draft.getEvaluatedRole(), criterionKey)) {
            throw new IllegalArgumentException("Unknown criterion key for role " + draft.getEvaluatedRole() + ": " + criterionKey);
        }

        // 2. Score range validation
        if (suggestion.getSuggestedRawScore() != null) {
            if (suggestion.getSuggestedRawScore().compareTo(BigDecimal.ZERO) < 0 ||
                suggestion.getSuggestedRawScore().compareTo(new BigDecimal("100")) > 0) {
                throw new IllegalArgumentException("Suggested score must be between 0 and 100");
            }

            // Non-null score requires explanation
            String explanation = suggestion.getEffectiveExplanation();
            if (explanation == null || explanation.isBlank()) {
                throw new IllegalArgumentException("Explanation is required when a non-null score is proposed");
            }
        }

        // 3. Confidence range validation
        if (suggestion.getConfidence() != null) {
            if (suggestion.getConfidence().compareTo(BigDecimal.ZERO) < 0 ||
                suggestion.getConfidence().compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("Confidence must be between 0 and 1");
            }
        }

        // 4. Evidence coverage range validation
        BigDecimal coverage = suggestion.getEffectiveEvidenceCoverage();
        if (coverage != null) {
            if (coverage.compareTo(BigDecimal.ZERO) < 0 ||
                coverage.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("Evidence coverage must be between 0 and 1");
            }
        }

        // 5. Missing evidence reference rejection
        List<String> evidenceIds = suggestion.getEvidenceIds();
        if (evidenceIds != null && !evidenceIds.isEmpty()) {
            boolean allFound = true;
            for (String id : evidenceIds) {
                boolean found = false;
                if (draft.getCriterionEvidence().containsKey(criterionKey)) {
                    found = draft.getCriterionEvidence().get(criterionKey).stream()
                            .anyMatch(e -> e.getEvidenceId().equals(id));
                }
                if (!found) {
                    allFound = false;
                    break;
                }
            }
            if (!allFound) {
                throw new IllegalArgumentException("Referenced evidence ID not found in draft for criterion " + criterionKey);
            }
        }

        // 6. Source field path structural validation
        List<String> sourceFieldPaths = suggestion.getSourceFieldPaths();
        if (sourceFieldPaths != null && !sourceFieldPaths.isEmpty()) {
            for (String path : sourceFieldPaths) {
                if (!SOURCE_PATH_PATTERN.matcher(path).matches()) {
                    throw new IllegalArgumentException("Invalid source field path format: " + path);
                }
            }
        }

        // 7. Null score may use NEEDS_MORE_DATA
        if (suggestion.getReviewStatus() == CriterionSuggestionReviewStatus.NEEDS_MORE_DATA) {
            // It's a reviewStatus
        }

        if (suggestion.getEffectiveReviewStatus() == CriterionSuggestionReviewStatus.NEEDS_MORE_DATA) {
            if (suggestion.getSuggestedRawScore() != null) {
                throw new IllegalArgumentException("NEEDS_MORE_DATA requires suggestedRawScore to be null");
            }
            if (suggestion.getEffectiveMissingData().isEmpty()) {
                throw new IllegalArgumentException("missingData must be non-empty when status is NEEDS_MORE_DATA");
            }
        }
    }
}
