package com.apms.domain.score.service;

import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.ai.dto.PartnerCriterionSuggestionResponse;
import com.apms.domain.score.draft.ApprovedSourceReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;

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
        List<ApprovedSourceReference> refs = new ArrayList<>();

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
        List<ApprovedSourceReference> refs = new ArrayList<>();

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
        List<ApprovedSourceReference> refs = new ArrayList<>();

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
        List<ApprovedSourceReference> refs = new ArrayList<>();

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
        List<ApprovedSourceReference> refs = new ArrayList<>();

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
        List<ApprovedSourceReference> refs = new ArrayList<>();

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
            validator.validateAndMap(json, "businessValueContributionScore", refs));
        assertTrue(ex.getMessage().contains("does not belong to the pinned source set"));
    }

    @Test
    void testRejectsEvidenceIdForAnotherCriterion() {
        String json = """
                {
                    "criterionKey": "businessValueContributionScore",
                    "rationale": "Rationale",
                    "evidenceReferenceIds": ["ref2"]
                }
                """;
        ApprovedSourceReference ref = new ApprovedSourceReference();
        ref.setReferenceId("ref2");
        ref.setCriterionKey("someOtherCriterion");

        List<ApprovedSourceReference> refs = List.of(ref);

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
            validator.validateAndMap(json, "businessValueContributionScore", refs));
        assertTrue(ex.getMessage().contains("is associated with a different criterion"));
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
        List<ApprovedSourceReference> refs = new ArrayList<>();

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
        List<ApprovedSourceReference> refs = new ArrayList<>();

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
            validator.validateAndMap(badJson, "businessValueContributionScore", refs));
        assertTrue(ex.getMessage().contains("Forbidden field found in AI response: overallScore"));
    }
}
