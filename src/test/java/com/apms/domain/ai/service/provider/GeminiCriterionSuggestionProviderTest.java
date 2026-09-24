//package com.apms.domain.ai.service.provider;
//
//import com.apms.common.exception.BusinessValidationException;
//import com.fasterxml.jackson.databind.ObjectMapper;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//
//import java.time.LocalDate;
//
//import static org.junit.jupiter.api.Assertions.assertThrows;
//
//class GeminiCriterionSuggestionProviderTest {
//
//    private GeminiCriterionSuggestionProvider provider;
//    private ObjectMapper objectMapper;
//
//    @BeforeEach
//    void setUp() {
//        objectMapper = new ObjectMapper();
//        // Since we are not doing live calls, we can't test full execution without mocking RestClient,
//        // but we can expose a parse method or test the validation logic if we mock RestClient.
//        provider = new GeminiCriterionSuggestionProvider(objectMapper, "dummy", "model");
//    }
//
//    @Test
//    void parseAndValidate_RejectsForbiddenFields() {
//        String base = "{ \"criterionKey\": \"marketPositionScore\", \"suggestedRawScore\": 50, \"explanation\": \"valid\", ";
//        CompetitorCriterionContext context = CompetitorCriterionContext.builder().criterionKey("marketPositionScore").build();
//
//        java.util.List<String> forbidden = java.util.List.of(
//            "\"overallScore\": 80",
//            "\"weights\": {}",
//            "\"ahpWeights\": {}",
//            "\"normalizedScores\": {}",
//            "\"evaluatedRole\": \"VENDOR\"",
//            "\"ruleSetVersion\": 1",
//            "\"weightVersion\": 1",
//            "\"managerConfirmed\": true",
//            "\"randomUnknownField\": \"xyz\""
//        );
//
//        for (String field : forbidden) {
//            String json = base + field + " }";
//            BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
//                provider.parseAndValidate(json, context)
//            );
//            org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("forbidden or unknown field"), "Failed to reject: " + field);
//        }
//    }
//
//    @Test
//    void parseAndValidate_RejectsMismatchingCriterionKey() {
//        String json = "{ \"criterionKey\": \"wrongKey\", \"suggestedRawScore\": 50, \"explanation\": \"valid\" }";
//        CompetitorCriterionContext context = CompetitorCriterionContext.builder().criterionKey("marketPositionScore").build();
//        assertThrows(BusinessValidationException.class, () ->
//            provider.parseAndValidate(json, context)
//        );
//    }
//
//    @Test
//    void parseAndValidate_RejectsScoreOutsideBounds() {
//        String jsonLow = "{ \"criterionKey\": \"marketPositionScore\", \"suggestedRawScore\": -10, \"explanation\": \"valid\" }";
//        CompetitorCriterionContext context = CompetitorCriterionContext.builder().criterionKey("marketPositionScore").build();
//        assertThrows(BusinessValidationException.class, () -> provider.parseAndValidate(jsonLow, context));
//
//        String jsonHigh = "{ \"criterionKey\": \"marketPositionScore\", \"suggestedRawScore\": 110, \"explanation\": \"valid\" }";
//        assertThrows(BusinessValidationException.class, () -> provider.parseAndValidate(jsonHigh, context));
//    }
//
//    @Test
//    void parseAndValidate_RejectsConfidenceOutsideBounds() {
//        String jsonLow = "{ \"criterionKey\": \"marketPositionScore\", \"suggestedRawScore\": 50, \"explanation\": \"valid\", \"confidence\": -0.5 }";
//        CompetitorCriterionContext context = CompetitorCriterionContext.builder().criterionKey("marketPositionScore").build();
//        assertThrows(BusinessValidationException.class, () -> provider.parseAndValidate(jsonLow, context));
//
//        String jsonHigh = "{ \"criterionKey\": \"marketPositionScore\", \"suggestedRawScore\": 50, \"explanation\": \"valid\", \"confidence\": 1.5 }";
//        assertThrows(BusinessValidationException.class, () -> provider.parseAndValidate(jsonHigh, context));
//    }
//
//    @Test
//    void parseAndValidate_RequiresMissingDataWhenScoreIsNull() {
//        String json = "{ \"criterionKey\": \"marketPositionScore\", \"suggestedRawScore\": null }";
//        CompetitorCriterionContext context = CompetitorCriterionContext.builder().criterionKey("marketPositionScore").build();
//        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
//            provider.parseAndValidate(json, context)
//        );
//        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("missingData is empty"));
//    }
//
//    @Test
//    void parseAndValidate_RequiresExplanationWhenScoreIsNotNull() {
//        String json = "{ \"criterionKey\": \"marketPositionScore\", \"suggestedRawScore\": 50 }";
//        CompetitorCriterionContext context = CompetitorCriterionContext.builder().criterionKey("marketPositionScore").build();
//        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
//            provider.parseAndValidate(json, context)
//        );
//        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("explanation is missing"));
//    }
//
//    @Test
//    void parseAndValidate_RejectsUnknownEvidenceReference() {
//        String json = "{ \"criterionKey\": \"marketPositionScore\", \"suggestedRawScore\": 50, \"explanation\": \"valid\", \"evidenceReferences\": [{\"evidenceId\": \"unknown-id\"}] }";
//        CompetitorCriterionContext context = CompetitorCriterionContext.builder().criterionKey("marketPositionScore").build();
//        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
//            provider.parseAndValidate(json, context)
//        );
//        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("Unknown evidence reference"));
//    }
//
//    @Test
//    void parseAndValidate_ValidatesCorrectEvidenceReference() throws Exception {
//        String json = "{ \"criterionKey\": \"marketPositionScore\", \"suggestedRawScore\": 50, \"explanation\": \"valid\", \"evidenceReferences\": [{\"evidenceId\": \"known-id\"}, {\"sourceFieldPath\": \"markets\"}] }";
//        CompetitorCriterionContext context = CompetitorCriterionContext.builder()
//            .criterionKey("marketPositionScore")
//            .draftEvidence(java.util.List.of(java.util.Map.of("evidenceId", "known-id")))
//            .referenceFacts(java.util.Map.of("markets", "EU"))
//            .build();
//
//        com.apms.domain.ai.dto.CriterionSuggestionOutput out = provider.parseAndValidate(json, context);
//        org.junit.jupiter.api.Assertions.assertEquals("known-id", out.getEvidenceReferences().get(0).getEvidenceId());
//    }
//
//    @Test
//    void parseAndValidate_ValidResponseContainingConfidenceAndAmbiguities() throws Exception {
//        String json = "{ \"criterionKey\": \"marketPositionScore\", \"suggestedRawScore\": 50, \"explanation\": \"valid\", \"confidence\": 0.9, \"ambiguities\": [\"test\"] }";
//        CompetitorCriterionContext context = CompetitorCriterionContext.builder().criterionKey("marketPositionScore").build();
//
//        com.apms.domain.ai.dto.CriterionSuggestionOutput out = provider.parseAndValidate(json, context);
//        org.junit.jupiter.api.Assertions.assertEquals(new java.math.BigDecimal("0.9"), out.getConfidence());
//        org.junit.jupiter.api.Assertions.assertEquals("test", out.getAmbiguities().get(0));
//    }
//
//    @Test
//    void parseAndValidate_MalformedJsonThrowsException() {
//        String json = "{ \"criterionKey\": \"marketPositionScore\", \"suggestedRawScore\": }";
//        CompetitorCriterionContext context = CompetitorCriterionContext.builder().criterionKey("marketPositionScore").build();
//        assertThrows(Exception.class, () -> provider.parseAndValidate(json, context));
//    }
//}
