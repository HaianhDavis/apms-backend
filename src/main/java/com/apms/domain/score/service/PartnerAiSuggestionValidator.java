package com.apms.domain.score.service;

import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.ai.dto.PartnerCriterionSuggestionResponse;
import com.apms.domain.score.draft.ApprovedSourceReference;
import com.apms.domain.score.registry.CanonicalRoleCriteria;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class PartnerAiSuggestionValidator {

    private final ObjectMapper objectMapper;

    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "criterionKey", "rationale", "missingDataNotes", "confidence", "evidenceReferenceIds"
    );

    private static final Set<String> FORBIDDEN_FIELDS = Set.of(
            "score", "rawScore", "suggestedRawScore", "criterionScore", "componentScores",
            "normalizedScore", "weightedScore", "weight", "weights", "ahpWeight",
            "overallScore", "companyRole", "relationshipType", "calculationDetails"
    );

    public PartnerCriterionSuggestionResponse validateAndMap(String jsonResponse, String requestedCriterionKey, List<ApprovedSourceReference> pinnedReferences) {
        try {
            JsonNode root = objectMapper.readTree(jsonResponse);
            
            // 1. Recursive JSON validation
            validateNodeRecursively(root);
            
            // 2. Map to DTO
            PartnerCriterionSuggestionResponse response = objectMapper.treeToValue(root, PartnerCriterionSuggestionResponse.class);
            
            // 3. Validate criterion
            if (response.getCriterionKey() == null || !response.getCriterionKey().equals(requestedCriterionKey)) {
                throw new BusinessValidationException("Response criterionKey does not match requested criterion");
            }
            if (!CanonicalRoleCriteria.PARTNER_CRITERIA.contains(response.getCriterionKey())) {
                throw new BusinessValidationException("Criterion is not a canonical PARTNER criterion");
            }
            
            // 4. Validate confidence
            if (response.getConfidence() != null) {
                if (response.getConfidence().compareTo(BigDecimal.ZERO) < 0 || response.getConfidence().compareTo(BigDecimal.ONE) > 0) {
                    throw new BusinessValidationException("Confidence must be between 0 and 1");
                }
            }
            
            // 5. Validate rationale
            if (response.getRationale() == null || response.getRationale().trim().isEmpty()) {
                throw new BusinessValidationException("Rationale must be non-blank");
            }
            
            // 6. Validate evidenceReferenceIds
            List<String> evidenceIds = response.getEvidenceReferenceIds();
            if (evidenceIds != null && !evidenceIds.isEmpty()) {
                Set<String> uniqueIds = new HashSet<>(evidenceIds);
                if (uniqueIds.size() != evidenceIds.size()) {
                    throw new BusinessValidationException("evidenceReferenceIds contains duplicates");
                }
                
                Set<String> validIds = new HashSet<>();
                for (ApprovedSourceReference ref : pinnedReferences) {
                    validIds.add(ref.getReferenceId());
                }
                
                for (String id : evidenceIds) {
                    if (!validIds.contains(id)) {
                        throw new BusinessValidationException("evidenceReferenceId " + id + " does not belong to the pinned source set");
                    }
                    
                    // Further criterion association validation
                    ApprovedSourceReference matchingRef = pinnedReferences.stream()
                            .filter(r -> r.getReferenceId().equals(id))
                            .findFirst().orElseThrow();
                    
                    if (matchingRef.getCriterionKey() != null && !matchingRef.getCriterionKey().equals(requestedCriterionKey)) {
                        // Assuming globally shared if criterionKey is null
                        throw new BusinessValidationException("evidenceReferenceId " + id + " is associated with a different criterion");
                    }
                }
            }
            
            return response;

        } catch (JsonProcessingException e) {
            throw new BusinessValidationException("Failed to parse AI response: " + e.getMessage());
        }
    }

    private void validateNodeRecursively(JsonNode node) {
        if (node.isObject()) {
            Iterator<String> fieldNames = node.fieldNames();
            while (fieldNames.hasNext()) {
                String fieldName = fieldNames.next();
                
                if (FORBIDDEN_FIELDS.contains(fieldName)) {
                    throw new BusinessValidationException("Forbidden field found in AI response: " + fieldName);
                }
                if (!ALLOWED_FIELDS.contains(fieldName)) {
                    throw new BusinessValidationException("Unknown field found in AI response: " + fieldName);
                }
                
                validateNodeRecursively(node.get(fieldName));
            }
        } else if (node.isArray()) {
            for (JsonNode element : node) {
                validateNodeRecursively(element);
            }
        }
    }
}
