package com.apms.domain.score.service;

import com.apms.domain.score.draft.AutomaticSuggestion;
import com.apms.domain.score.draft.RoleEvaluationDraft;
import com.apms.domain.score.dto.draft.CompetitorCriterionContext;
import com.apms.domain.score.dto.draft.GenerateSuggestionRequest;
import com.apms.domain.score.enums.RoleEvaluationStatus;
import com.apms.domain.score.enums.CriterionSuggestionReviewStatus;
import com.apms.domain.ai.service.provider.CriterionSuggestionProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CompetitorSuggestionGenerationServicePreconditionsTest {

    private CompetitorSuggestionGenerationService service;
    private CompetitorCriterionEvidenceService evidenceService;
    private CriterionSuggestionProvider mockAiProvider;

    @BeforeEach
    void setUp() {
        evidenceService = mock(CompetitorCriterionEvidenceService.class);
        mockAiProvider = mock(CriterionSuggestionProvider.class);
        service = new CompetitorSuggestionGenerationService(
                mock(CompetitorComparisonService.class),
                evidenceService,
                mockAiProvider,
                mock(com.apms.domain.score.repository.mongo.RoleEvaluationDraftRepository.class),
                new ObjectMapper()
        );
    }

    private RoleEvaluationDraft createDraft() {
        RoleEvaluationDraft draft = new RoleEvaluationDraft();
        draft.setStatus(RoleEvaluationStatus.DRAFT);
        return draft;
    }

    private void mockAiSuccess(String criterionKey) {
        com.apms.domain.ai.dto.CriterionSuggestionOutput output = new com.apms.domain.ai.dto.CriterionSuggestionOutput();
        output.setCriterionKey(criterionKey);
        output.setSuggestedRawScore(new java.math.BigDecimal("75.00"));
        output.setExplanation("Valid");
        when(mockAiProvider.generateCriterionSuggestion(any(), any())).thenReturn(output);
    }

    // --- MARKET POSITION ---
    @Test
    void testMarketPosition_ComparativeMarketDataPasses() {
        mockAiSuccess("marketPositionScore");
        CompetitorCriterionContext ctx = CompetitorCriterionContext.builder()
                .criterionKey("marketPositionScore")
                .targetFacts(Map.of("markets", "EU", "marketShare", "10%"))
                .referenceFacts(Map.of("markets", "EU", "marketShare", "15%"))
                .build();
        when(evidenceService.buildContext(any(), eq("marketPositionScore"), any(), any())).thenReturn(ctx);

        RoleEvaluationDraft draft = createDraft();
        assertEquals("GENERATED", service.generateSingleAndSave(draft, "marketPositionScore", new GenerateSuggestionRequest()));

        AutomaticSuggestion suggestion = draft.getAutomaticSuggestions().get("marketPositionScore");
        assertEquals(new java.math.BigDecimal("75.00"), suggestion.getSuggestedRawScore());
        assertEquals(com.apms.domain.score.enums.CriterionSuggestionMethod.AI_ASSISTED, suggestion.getMethod());
        assertEquals(com.apms.domain.score.enums.CriterionSuggestionValidationStatus.PASS, suggestion.getValidationStatus());
        assertEquals(com.apms.domain.score.enums.CriterionSuggestionReviewStatus.PENDING, suggestion.getReviewStatus());
        org.mockito.Mockito.verify(mockAiProvider, org.mockito.Mockito.times(1)).generateCriterionSuggestion(any(), any());
    }

    @Test
    void testMarketPosition_TargetOnlyMarketDataReturnsNeedsMoreData() {
        CompetitorCriterionContext ctx = CompetitorCriterionContext.builder()
                .criterionKey("marketPositionScore")
                .targetFacts(Map.of("markets", "EU", "marketShare", "10%"))
                .referenceFacts(new HashMap<>())
                .build();
        when(evidenceService.buildContext(any(), eq("marketPositionScore"), any(), any())).thenReturn(ctx);
        assertEquals("NEEDS_MORE_DATA", service.generateSingleAndSave(createDraft(), "marketPositionScore", new GenerateSuggestionRequest()));
    }

    // --- CAPABILITY ---
    @Test
    void testCapability_BothCompaniesWithConcreteCapabilitiesGeneratesSuggestion() {
        mockAiSuccess("competitiveCapabilityScore");
        CompetitorCriterionContext ctx = CompetitorCriterionContext.builder()
                .criterionKey("competitiveCapabilityScore")
                .targetFacts(Map.of("technologyCapabilities", "A"))
                .referenceFacts(Map.of("patents", "B"))
                .build();
        when(evidenceService.buildContext(any(), eq("competitiveCapabilityScore"), any(), any())).thenReturn(ctx);
        RoleEvaluationDraft draft = createDraft();
        assertEquals("GENERATED", service.generateSingleAndSave(draft, "competitiveCapabilityScore", new GenerateSuggestionRequest()));

        AutomaticSuggestion suggestion = draft.getAutomaticSuggestions().get("competitiveCapabilityScore");
        assertEquals(new java.math.BigDecimal("75.00"), suggestion.getSuggestedRawScore());
        assertEquals(com.apms.domain.score.enums.CriterionSuggestionMethod.AI_ASSISTED, suggestion.getMethod());
        assertEquals(com.apms.domain.score.enums.CriterionSuggestionValidationStatus.PASS, suggestion.getValidationStatus());
        assertEquals(com.apms.domain.score.enums.CriterionSuggestionReviewStatus.PENDING, suggestion.getReviewStatus());
        org.mockito.Mockito.verify(mockAiProvider, org.mockito.Mockito.times(1)).generateCriterionSuggestion(any(), any());
    }

    @Test
    void testCapability_ProductsOnlyReturnsNeedsMoreData() {
        CompetitorCriterionContext ctx = CompetitorCriterionContext.builder()
                .criterionKey("competitiveCapabilityScore")
                .targetFacts(Map.of("products", "A"))
                .referenceFacts(Map.of("products", "B"))
                .build();
        when(evidenceService.buildContext(any(), eq("competitiveCapabilityScore"), any(), any())).thenReturn(ctx);
        assertEquals("NEEDS_MORE_DATA", service.generateSingleAndSave(createDraft(), "competitiveCapabilityScore", new GenerateSuggestionRequest()));
    }

    @Test
    void testCapability_MissingFPTCapabilitiesReturnsNeedsMoreData() {
        CompetitorCriterionContext ctx = CompetitorCriterionContext.builder()
                .criterionKey("competitiveCapabilityScore")
                .targetFacts(Map.of("products", "A"))
                .referenceFacts(new HashMap<>())
                .build();
        when(evidenceService.buildContext(any(), eq("competitiveCapabilityScore"), any(), any())).thenReturn(ctx);
        assertEquals("NEEDS_MORE_DATA", service.generateSingleAndSave(createDraft(), "competitiveCapabilityScore", new GenerateSuggestionRequest()));
    }

    @Test
    void testCapability_EmployeeCountOnlyReturnsNeedsMoreData() {
        CompetitorCriterionContext ctx = CompetitorCriterionContext.builder()
                .criterionKey("competitiveCapabilityScore")
                .targetFacts(Map.of("employeeCount", "100"))
                .referenceFacts(Map.of("employeeCount", "200"))
                .build();
        when(evidenceService.buildContext(any(), eq("competitiveCapabilityScore"), any(), any())).thenReturn(ctx);
        assertEquals("NEEDS_MORE_DATA", service.generateSingleAndSave(createDraft(), "competitiveCapabilityScore", new GenerateSuggestionRequest()));
    }

    @Test
    void testCapability_MissingReferenceCapabilityReturnsNeedsMoreData() {
        CompetitorCriterionContext ctx = CompetitorCriterionContext.builder()
                .criterionKey("competitiveCapabilityScore")
                .targetFacts(Map.of("technologyCapabilities", "A"))
                .referenceFacts(new HashMap<>())
                .build();
        when(evidenceService.buildContext(any(), eq("competitiveCapabilityScore"), any(), any())).thenReturn(ctx);
        assertEquals("NEEDS_MORE_DATA", service.generateSingleAndSave(createDraft(), "competitiveCapabilityScore", new GenerateSuggestionRequest()));
    }

    // --- STRATEGIC INTENT ---
    @Test
    void testStrategicIntent_ConfirmedStrategicActionPasses() {
        mockAiSuccess("strategicIntentScore");
        CompetitorCriterionContext ctx = CompetitorCriterionContext.builder()
                .criterionKey("strategicIntentScore")
                .externalSignals(List.of(Map.of("category", "STRATEGIC_ANNOUNCEMENT")))
                .build();
        when(evidenceService.buildContext(any(), eq("strategicIntentScore"), any(), any())).thenReturn(ctx);
        RoleEvaluationDraft draft = createDraft();
        assertEquals("GENERATED", service.generateSingleAndSave(draft, "strategicIntentScore", new GenerateSuggestionRequest()));

        AutomaticSuggestion suggestion = draft.getAutomaticSuggestions().get("strategicIntentScore");
        assertEquals(new java.math.BigDecimal("75.00"), suggestion.getSuggestedRawScore());
        assertEquals(com.apms.domain.score.enums.CriterionSuggestionMethod.AI_ASSISTED, suggestion.getMethod());
        assertEquals(com.apms.domain.score.enums.CriterionSuggestionValidationStatus.PASS, suggestion.getValidationStatus());
        assertEquals(com.apms.domain.score.enums.CriterionSuggestionReviewStatus.PENDING, suggestion.getReviewStatus());
        org.mockito.Mockito.verify(mockAiProvider, org.mockito.Mockito.times(1)).generateCriterionSuggestion(any(), any());
    }

    @Test
    void testStrategicIntent_GenericCompanyNewsAloneIsInsufficient() {
        CompetitorCriterionContext ctx = CompetitorCriterionContext.builder()
                .criterionKey("strategicIntentScore")
                .externalSignals(List.of(Map.of("category", "COMPANY_NEWS", "title", "hello", "summary", "world")))
                .build();
        when(evidenceService.buildContext(any(), eq("strategicIntentScore"), any(), any())).thenReturn(ctx);
        assertEquals("NEEDS_MORE_DATA", service.generateSingleAndSave(createDraft(), "strategicIntentScore", new GenerateSuggestionRequest()));
    }

    // --- GROWTH MOMENTUM ---
    @Test
    void testGrowthMomentum_PeriodDefinedNumericGrowthGeneratesSuggestion() {
        mockAiSuccess("growthMomentumScore");
        CompetitorCriterionContext ctx = CompetitorCriterionContext.builder()
                .criterionKey("growthMomentumScore")
                .periodStart(LocalDate.now().minusDays(30))
                .periodEnd(LocalDate.now())
                .externalSignals(List.of(Map.of("category", "NEWS", "title", "revenue growth 10%")))
                .build();
        when(evidenceService.buildContext(any(), eq("growthMomentumScore"), any(), any())).thenReturn(ctx);
        RoleEvaluationDraft draft = createDraft();
        assertEquals("GENERATED", service.generateSingleAndSave(draft, "growthMomentumScore", new GenerateSuggestionRequest()));

        AutomaticSuggestion suggestion = draft.getAutomaticSuggestions().get("growthMomentumScore");
        assertEquals(new java.math.BigDecimal("75.00"), suggestion.getSuggestedRawScore());
        assertEquals(com.apms.domain.score.enums.CriterionSuggestionMethod.AI_ASSISTED, suggestion.getMethod());
        assertEquals(com.apms.domain.score.enums.CriterionSuggestionValidationStatus.PASS, suggestion.getValidationStatus());
        assertEquals(com.apms.domain.score.enums.CriterionSuggestionReviewStatus.PENDING, suggestion.getReviewStatus());
        org.mockito.Mockito.verify(mockAiProvider, org.mockito.Mockito.times(1)).generateCriterionSuggestion(any(), any());
    }

    @Test
    void testGrowthMomentum_SameStartAndEndDateRejected() {
        CompetitorCriterionContext ctx = CompetitorCriterionContext.builder()
                .criterionKey("growthMomentumScore")
                .periodStart(LocalDate.now())
                .periodEnd(LocalDate.now())
                .externalSignals(List.of(Map.of("category", "NEWS", "title", "revenue growth 10%")))
                .build();
        when(evidenceService.buildContext(any(), eq("growthMomentumScore"), any(), any())).thenReturn(ctx);
        assertEquals("NEEDS_MORE_DATA", service.generateSingleAndSave(createDraft(), "growthMomentumScore", new GenerateSuggestionRequest()));
    }

    @Test
    void testGrowthMomentum_GenericNewsTitleReturnsNeedsMoreData() {
        CompetitorCriterionContext ctx = CompetitorCriterionContext.builder()
                .criterionKey("growthMomentumScore")
                .periodStart(LocalDate.now().minusDays(30))
                .periodEnd(LocalDate.now())
                .externalSignals(List.of(Map.of("category", "NEWS", "title", "Company News", "summary", "")))
                .build();
        when(evidenceService.buildContext(any(), eq("growthMomentumScore"), any(), any())).thenReturn(ctx);
        assertEquals("NEEDS_MORE_DATA", service.generateSingleAndSave(createDraft(), "growthMomentumScore", new GenerateSuggestionRequest()));
    }

    @Test
    void testGrowthMomentum_ExplicitRevenueGrowthFactGeneratesSuggestion() {
        mockAiSuccess("growthMomentumScore");
        CompetitorCriterionContext ctx = CompetitorCriterionContext.builder()
                .criterionKey("growthMomentumScore")
                .periodStart(LocalDate.now().minusDays(30))
                .periodEnd(LocalDate.now())
                .targetFacts(Map.of("revenueGrowth", "15%"))
                .build();
        when(evidenceService.buildContext(any(), eq("growthMomentumScore"), any(), any())).thenReturn(ctx);
        assertEquals("GENERATED", service.generateSingleAndSave(createDraft(), "growthMomentumScore", new GenerateSuggestionRequest()));
    }

    @Test
    void testGrowthMomentum_PeriodStartAfterPeriodEndRejected() {
        CompetitorCriterionContext ctx = CompetitorCriterionContext.builder()
                .criterionKey("growthMomentumScore")
                .periodStart(LocalDate.now())
                .periodEnd(LocalDate.now().minusDays(30))
                .targetFacts(Map.of("revenueGrowth", "15%"))
                .build();
        when(evidenceService.buildContext(any(), eq("growthMomentumScore"), any(), any())).thenReturn(ctx);
        assertEquals("NEEDS_MORE_DATA", service.generateSingleAndSave(createDraft(), "growthMomentumScore", new GenerateSuggestionRequest()));
    }

    @Test
    void testGrowthMomentum_GenericNewsWithOnlyWordGrowthReturnsNeedsMoreData() {
        CompetitorCriterionContext ctx = CompetitorCriterionContext.builder()
                .criterionKey("growthMomentumScore")
                .periodStart(LocalDate.now().minusDays(30))
                .periodEnd(LocalDate.now())
                .externalSignals(List.of(Map.of("category", "NEWS", "title", "We had a lot of growth", "summary", "")))
                .build();
        when(evidenceService.buildContext(any(), eq("growthMomentumScore"), any(), any())).thenReturn(ctx);
        assertEquals("NEEDS_MORE_DATA", service.generateSingleAndSave(createDraft(), "growthMomentumScore", new GenerateSuggestionRequest()));
    }

    @Test
    void testGrowthMomentum_DatedExpansionOrFundingEvidenceGeneratesSuggestion() {
        mockAiSuccess("growthMomentumScore");
        CompetitorCriterionContext ctx = CompetitorCriterionContext.builder()
                .criterionKey("growthMomentumScore")
                .periodStart(LocalDate.now().minusDays(30))
                .periodEnd(LocalDate.now())
                .externalSignals(List.of(Map.of("category", "NEWS", "title", "Series B funding of 50M", "date", LocalDate.now().toString(), "source", "TechCrunch")))
                .build();
        when(evidenceService.buildContext(any(), eq("growthMomentumScore"), any(), any())).thenReturn(ctx);
        assertEquals("GENERATED", service.generateSingleAndSave(createDraft(), "growthMomentumScore", new GenerateSuggestionRequest()));
    }

    @Test
    void testGrowthMomentum_StaticRevenueAloneReturnsNeedsMoreData() {
        CompetitorCriterionContext ctx = CompetitorCriterionContext.builder()
                .criterionKey("growthMomentumScore")
                .periodStart(LocalDate.now().minusDays(30))
                .periodEnd(LocalDate.now())
                .targetFacts(Map.of("revenue", "1M")) // Static revenue
                .externalSignals(List.of())
                .build();
        when(evidenceService.buildContext(any(), eq("growthMomentumScore"), any(), any())).thenReturn(ctx);
        assertEquals("NEEDS_MORE_DATA", service.generateSingleAndSave(createDraft(), "growthMomentumScore", new GenerateSuggestionRequest()));
    }

    @Test
    void testGrowthMomentum_StaticEmployeeCountReturnsNeedsMoreData() {
        CompetitorCriterionContext ctx = CompetitorCriterionContext.builder()
                .criterionKey("growthMomentumScore")
                .periodStart(LocalDate.now().minusDays(30))
                .periodEnd(LocalDate.now())
                .targetFacts(Map.of("employeeCount", "100")) // Static employee count
                .externalSignals(List.of())
                .build();
        when(evidenceService.buildContext(any(), eq("growthMomentumScore"), any(), any())).thenReturn(ctx);
        assertEquals("NEEDS_MORE_DATA", service.generateSingleAndSave(createDraft(), "growthMomentumScore", new GenerateSuggestionRequest()));
    }

    // --- COMPETITIVE THREAT ---
    @Test
    void testCompetitiveThreat_StructuredFPTSpecificDirectEvidenceGeneratesSuggestion() {
        mockAiSuccess("competitiveThreatScore");
        CompetitorCriterionContext ctx = CompetitorCriterionContext.builder()
                .criterionKey("competitiveThreatScore")
                .draftEvidence(List.of(Map.of("eventType", "CUSTOMER_LOSS_TO_TARGET", "referenceCompanyId", "REF", "targetCompanyId", "TGT", "evidenceId", "E1", "evidenceDate", LocalDate.now())))
                .build();
        when(evidenceService.buildContext(any(), eq("competitiveThreatScore"), any(), any())).thenReturn(ctx);
        RoleEvaluationDraft draft = createDraft();
        assertEquals("GENERATED", service.generateSingleAndSave(draft, "competitiveThreatScore", new GenerateSuggestionRequest()));

        AutomaticSuggestion suggestion = draft.getAutomaticSuggestions().get("competitiveThreatScore");
        assertEquals(new java.math.BigDecimal("75.00"), suggestion.getSuggestedRawScore());
        assertEquals(com.apms.domain.score.enums.CriterionSuggestionMethod.AI_ASSISTED, suggestion.getMethod());
        assertEquals(com.apms.domain.score.enums.CriterionSuggestionValidationStatus.PASS, suggestion.getValidationStatus());
        assertEquals(com.apms.domain.score.enums.CriterionSuggestionReviewStatus.PENDING, suggestion.getReviewStatus());
        org.mockito.Mockito.verify(mockAiProvider, org.mockito.Mockito.times(1)).generateCriterionSuggestion(any(), any());
    }

    @Test
    void testCompetitiveThreat_GenericLostBidToTargetTextReturnsNeedsMoreData() {
        CompetitorCriterionContext ctx = CompetitorCriterionContext.builder()
                .criterionKey("competitiveThreatScore")
                .draftEvidence(List.of(Map.of("note", "lost bid to target")))
                .build();
        when(evidenceService.buildContext(any(), eq("competitiveThreatScore"), any(), any())).thenReturn(ctx);
        assertEquals("NEEDS_MORE_DATA", service.generateSingleAndSave(createDraft(), "competitiveThreatScore", new GenerateSuggestionRequest()));
    }

    @Test
    void testCompetitiveThreat_WrongReferenceCompanyReturnsNeedsMoreData() {
        Map<String, Object> evidence = new HashMap<>();
        evidence.put("eventType", "CUSTOMER_LOSS_TO_TARGET");
        evidence.put("referenceCompanyId", null);
        evidence.put("targetCompanyId", "TGT");
        evidence.put("evidenceId", "E1");
        evidence.put("evidenceDate", LocalDate.now());

        CompetitorCriterionContext ctx = CompetitorCriterionContext.builder()
                .criterionKey("competitiveThreatScore")
                .draftEvidence(List.of(evidence))
                .build();
        when(evidenceService.buildContext(any(), eq("competitiveThreatScore"), any(), any())).thenReturn(ctx);
        assertEquals("NEEDS_MORE_DATA", service.generateSingleAndSave(createDraft(), "competitiveThreatScore", new GenerateSuggestionRequest()));
    }

    @Test
    void testCompetitiveThreat_WrongTargetCompanyReturnsNeedsMoreData() {
        Map<String, Object> evidence = new HashMap<>();
        evidence.put("eventType", "CUSTOMER_LOSS_TO_TARGET");
        evidence.put("referenceCompanyId", "REF");
        evidence.put("targetCompanyId", null);
        evidence.put("evidenceId", "E1");
        evidence.put("evidenceDate", LocalDate.now());

        CompetitorCriterionContext ctx = CompetitorCriterionContext.builder()
                .criterionKey("competitiveThreatScore")
                .draftEvidence(List.of(evidence))
                .build();
        when(evidenceService.buildContext(any(), eq("competitiveThreatScore"), any(), any())).thenReturn(ctx);
        assertEquals("NEEDS_MORE_DATA", service.generateSingleAndSave(createDraft(), "competitiveThreatScore", new GenerateSuggestionRequest()));
    }

    @Test
    void testCompetitiveThreat_GenericCompetitorStrengthReturnsNeedsMoreData() {
        CompetitorCriterionContext ctx = CompetitorCriterionContext.builder()
                .criterionKey("competitiveThreatScore")
                .draftEvidence(List.of(Map.of("eventType", "UNKNOWN_EVENT", "referenceCompanyId", "REF", "targetCompanyId", "TGT", "evidenceId", "E1", "evidenceDate", LocalDate.now())))
                .build();
        when(evidenceService.buildContext(any(), eq("competitiveThreatScore"), any(), any())).thenReturn(ctx);
        assertEquals("NEEDS_MORE_DATA", service.generateSingleAndSave(createDraft(), "competitiveThreatScore", new GenerateSuggestionRequest()));
    }

    @Test
    void testCompetitiveThreat_MissingProvenanceReturnsNeedsMoreData() {
        Map<String, Object> evidence = new HashMap<>();
        evidence.put("eventType", "CUSTOMER_LOSS_TO_TARGET");
        evidence.put("referenceCompanyId", "REF");
        evidence.put("targetCompanyId", "TGT");
        // Intentionally missing source, evidenceId, evidenceDate

        CompetitorCriterionContext ctx = CompetitorCriterionContext.builder()
                .criterionKey("competitiveThreatScore")
                .draftEvidence(List.of(evidence))
                .build();
        when(evidenceService.buildContext(any(), eq("competitiveThreatScore"), any(), any())).thenReturn(ctx);
        assertEquals("NEEDS_MORE_DATA", service.generateSingleAndSave(createDraft(), "competitiveThreatScore", new GenerateSuggestionRequest()));
    }
}
