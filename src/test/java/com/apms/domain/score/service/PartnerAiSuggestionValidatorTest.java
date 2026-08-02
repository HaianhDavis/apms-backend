package com.apms.domain.score.service;

import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.ai.dto.PartnerCriterionSuggestionResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PartnerAiSuggestionValidatorTest {

    private PartnerAiSuggestionValidator validator;

    @BeforeEach
    void setUp() {
        validator = new PartnerAiSuggestionValidator(new ObjectMapper());
    }

    @Test
    void testValidResponse() {
        String json = """
                {
                    "criterionKey": "businessValueContributionScore",
                    "rationale": "Valid rationale",
                    "confidence": 0.85,
                    "evidenceReferenceIds": []
                }
                """;
        Set<String> refs = Set.of();

        PartnerCriterionSuggestionResponse resp = validator.validateAndMap(json, "businessValueContributionScore", refs);
        assertNotNull(resp);
        assertEquals("businessValueContributionScore", resp.getCriterionKey());
    }

    @Test
    void testRejectsForbiddenScoreField() {
        String json = """
                {
                    "criterionKey": "businessValueContributionScore",
                    "rationale": "Rationale",
                    "score": 85.0
                }
                """;
        Set<String> refs = Set.of();

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
            validator.validateAndMap(json, "businessValueContributionScore", refs));
        assertTrue(ex.getMessage().contains("Forbidden field"));
    }

    @Test
    void testRejectsNestedForbiddenField() {
        String badJson = """
                {
                    "criterionKey": "businessValueContributionScore",
                    "rationale": "Rationale",
                    "calculationDetails": {
                        "score": 10
                    }
                }
                """;
        Set<String> refs = Set.of();

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
            validator.validateAndMap(badJson, "businessValueContributionScore", refs));
        assertTrue(ex.getMessage().contains("Forbidden field found in AI response: calculationDetails") ||
                   ex.getMessage().contains("Forbidden field found in AI response: score"));
    }

    @Test
    void testRejectsNestedUnknownField() {
        String badJson = """
                {
                    "criterionKey": "businessValueContributionScore",
                    "rationale": "Rationale",
                    "nested": {
                        "foo": "bar"
                    }
                }
                """;
        Set<String> refs = Set.of();

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
            validator.validateAndMap(badJson, "businessValueContributionScore", refs));
        assertTrue(ex.getMessage().contains("Unknown field found in AI response: nested"));
    }

    @Test
    void testRejectsDuplicateEvidenceIds() {
        String json = """
                {
                    "criterionKey": "businessValueContributionScore",
                    "rationale": "Rationale",
                    "evidenceReferenceIds": ["ref1", "ref1"]
                }
                """;
        Set<String> refs = Set.of();

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
            validator.validateAndMap(json, "businessValueContributionScore", refs));
        assertTrue(ex.getMessage().contains("evidenceReferenceIds contains duplicates"));
    }

    @Test
    void testRejectsEvidenceIdNotPinned() {
        String json = """
                {
                    "criterionKey": "businessValueContributionScore",
                    "rationale": "Rationale",
                    "evidenceReferenceIds": ["mongo_id_1234"]
                }
                """;
        Set<String> refs = Set.of();

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
            validator.validateAndMap(json, "businessValueContributionScore", refs));
        assertTrue(ex.getMessage().contains("does not belong to the selected evidence set"));
    }

    @Test
    void testAcceptsSelectedEvidenceId() {
        String json = """
                {
                    "criterionKey": "businessValueContributionScore",
                    "rationale": "Rationale",
                    "evidenceReferenceIds": ["ref2"]
                }
                """;

        Set<String> refs = Set.of("ref2");

        PartnerCriterionSuggestionResponse resp = validator.validateAndMap(json, "businessValueContributionScore", refs);
        assertEquals("businessValueContributionScore", resp.getCriterionKey());
    }

    @Test
    void testRejectsArrayObjectWithForbiddenScore() {
        String badJson = """
                {
                    "criterionKey": "businessValueContributionScore",
                    "rationale": "Rationale",
                    "missingDataNotes": [
                        { "note": "No data", "score": 0 }
                    ]
                }
                """;
        Set<String> refs = Set.of();

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
            validator.validateAndMap(badJson, "businessValueContributionScore", refs));
        assertTrue(ex.getMessage().contains("Forbidden field found in AI response: score") || ex.getMessage().contains("Unknown field found in AI response"));
    }

    @Test
    void testRejectsOverallScore() {
        String badJson = """
                {
                    "criterionKey": "businessValueContributionScore",
                    "rationale": "Rationale",
                    "overallScore": 90
                }
                """;
        Set<String> refs = Set.of();

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
            validator.validateAndMap(badJson, "businessValueContributionScore", refs));
        assertTrue(ex.getMessage().contains("Forbidden field found in AI response: overallScore"));
    }
}
