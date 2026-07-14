package com.apms.domain.score.service;

import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.ai.dto.CriterionSuggestionOutput;
import com.apms.domain.ai.service.provider.CriterionSuggestionProvider;
import com.apms.domain.score.draft.AutomaticSuggestion;
import com.apms.domain.score.draft.RoleEvaluationDraft;
import com.apms.domain.score.dto.draft.CompetitorCriterionContext;
import com.apms.domain.score.dto.draft.GenerateSuggestionRequest;
import com.apms.domain.score.enums.RoleEvaluationStatus;
import com.apms.domain.score.enums.CriterionSuggestionReviewStatus;
import com.apms.domain.score.enums.CriterionSuggestionValidationStatus;
import com.apms.domain.score.repository.mongo.RoleEvaluationDraftRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class CompetitorSuggestionGenerationServiceTest {

    private CompetitorSuggestionGenerationService service;
    private CompetitorComparisonService comparisonService;
    private CompetitorCriterionEvidenceService evidenceService;
    private CriterionSuggestionProvider aiProvider;
    private RoleEvaluationDraftRepository draftRepository;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        comparisonService = mock(CompetitorComparisonService.class);
        evidenceService = mock(CompetitorCriterionEvidenceService.class);
        aiProvider = mock(CriterionSuggestionProvider.class);
        draftRepository = mock(RoleEvaluationDraftRepository.class);
        objectMapper = new ObjectMapper();

        service = new CompetitorSuggestionGenerationService(
                comparisonService, evidenceService, aiProvider, draftRepository, objectMapper);
    }

    @Test
    void generateSingle_RequiresEditableDraft() {
        RoleEvaluationDraft draft = new RoleEvaluationDraft();
        draft.setStatus(RoleEvaluationStatus.IN_REVIEW);

        org.junit.jupiter.api.Assertions.assertThrows(BusinessValidationException.class, () -> {
            service.generateSingleAndSave(draft, "marketPositionScore", new GenerateSuggestionRequest());
        });
    }

    @Test
    void generateSingle_FailsPreconditions_ReturnsNeedsMoreData() {
        RoleEvaluationDraft draft = new RoleEvaluationDraft();
        draft.setStatus(RoleEvaluationStatus.DRAFT);

        CompetitorCriterionContext context = CompetitorCriterionContext.builder()
                .criterionKey("marketPositionScore")
                .targetFacts(new HashMap<>())
                .referenceFacts(new HashMap<>())
                .build();

        when(evidenceService.buildContext(any(), eq("marketPositionScore"), any(), any())).thenReturn(context);

        String outcome = service.generateSingleAndSave(draft, "marketPositionScore", new GenerateSuggestionRequest());

        assertEquals("NEEDS_MORE_DATA", outcome);
        AutomaticSuggestion suggestion = draft.getAutomaticSuggestions().get("marketPositionScore");
        assertNotNull(suggestion);
        assertEquals(CriterionSuggestionValidationStatus.WARNING, suggestion.getValidationStatus());
        assertEquals(CriterionSuggestionReviewStatus.NEEDS_MORE_DATA, suggestion.getReviewStatus());
    }

    @Test
    void generateSingle_TechnicalFailure_ReturnsTechnicalFailure() {
        RoleEvaluationDraft draft = new RoleEvaluationDraft();
        draft.setStatus(RoleEvaluationStatus.DRAFT);

        CompetitorCriterionContext context = CompetitorCriterionContext.builder()
                .criterionKey("marketPositionScore")
                .targetFacts(java.util.Map.of("markets", "data", "marketShare", "10%"))
                .referenceFacts(java.util.Map.of("markets", "data", "marketShare", "20%"))
                .build();

        when(evidenceService.buildContext(any(), eq("marketPositionScore"), any(), any())).thenReturn(context);
        when(aiProvider.generateCriterionSuggestion(any(), any())).thenThrow(new RuntimeException("API down"));

        String outcome = service.generateSingleAndSave(draft, "marketPositionScore", new GenerateSuggestionRequest());

        assertEquals("TECHNICAL_FAILURE", outcome);
        AutomaticSuggestion suggestion = draft.getAutomaticSuggestions().get("marketPositionScore");
        assertEquals(CriterionSuggestionValidationStatus.FAIL, suggestion.getValidationStatus());
    }

    @Test
    void generateSingle_Success_ReturnsGenerated() {
        RoleEvaluationDraft draft = new RoleEvaluationDraft();
        draft.setStatus(RoleEvaluationStatus.DRAFT);

        CompetitorCriterionContext context = CompetitorCriterionContext.builder()
                .criterionKey("marketPositionScore")
                .targetFacts(java.util.Map.of("markets", "data", "marketShare", "10%"))
                .referenceFacts(java.util.Map.of("markets", "data", "marketShare", "20%"))
                .build();

        when(evidenceService.buildContext(any(), eq("marketPositionScore"), any(), any())).thenReturn(context);

        CriterionSuggestionOutput output = new CriterionSuggestionOutput();
        output.setCriterionKey("marketPositionScore");
        output.setSuggestedRawScore(new BigDecimal("75.0"));
        output.setExplanation("Test explanation");

        when(aiProvider.generateCriterionSuggestion(any(), any())).thenReturn(output);

        String outcome = service.generateSingleAndSave(draft, "marketPositionScore", new GenerateSuggestionRequest());

        assertEquals("GENERATED", outcome);
        AutomaticSuggestion suggestion = draft.getAutomaticSuggestions().get("marketPositionScore");
        assertEquals(CriterionSuggestionValidationStatus.PASS, suggestion.getValidationStatus());
        assertEquals(CriterionSuggestionReviewStatus.PENDING, suggestion.getReviewStatus());
        assertEquals(new BigDecimal("75.0"), suggestion.getSuggestedRawScore());
    }

    @Test
    void generateSingle_RegenerationProtection_Accepted() {
        RoleEvaluationDraft draft = new RoleEvaluationDraft();
        draft.setStatus(RoleEvaluationStatus.DRAFT);

        AutomaticSuggestion existing = new AutomaticSuggestion();
        existing.setReviewStatus(CriterionSuggestionReviewStatus.ACCEPTED);
        draft.getAutomaticSuggestions().put("marketPositionScore", existing);

        String outcome = service.generateSingleAndSave(draft, "marketPositionScore", new GenerateSuggestionRequest());
        assertEquals("PROTECTED_FROM_OVERWRITE", outcome);

        GenerateSuggestionRequest forcedReq = new GenerateSuggestionRequest();
        forcedReq.setForce(true);
        outcome = service.generateSingleAndSave(draft, "marketPositionScore", forcedReq);
        assertEquals("PROTECTED_FROM_OVERWRITE", outcome); // No commen

        forcedReq.setReviewComment("regenerate please");

        CompetitorCriterionContext context = CompetitorCriterionContext.builder()
            .criterionKey("marketPositionScore")
            .targetFacts(java.util.Map.of("markets", "data", "marketShare", "10%"))
            .referenceFacts(java.util.Map.of("markets", "data", "marketShare", "20%"))
            .build();
        when(evidenceService.buildContext(any(), eq("marketPositionScore"), any(), any())).thenReturn(context);

        CriterionSuggestionOutput output = new CriterionSuggestionOutput();
        output.setCriterionKey("marketPositionScore");
        output.setSuggestedRawScore(new BigDecimal("80"));
        when(aiProvider.generateCriterionSuggestion(any(), any())).thenReturn(output);

        outcome = service.generateSingleAndSave(draft, "marketPositionScore", forcedReq);
        assertEquals("GENERATED", outcome);
    }

    @Test
    void generateAll_PartialBatchBehavior() {
        RoleEvaluationDraft draft = new RoleEvaluationDraft();
        draft.setStatus(RoleEvaluationStatus.DRAFT);

        when(comparisonService.suggestProductMarketOverlap(any(), any(), eq(com.apms.domain.score.enums.OverlapSuggestionMode.CANONICAL_STRICT))).thenReturn(new AutomaticSuggestion());

        // Mocks for preconditions
        when(evidenceService.buildContext(any(), eq("marketPositionScore"), any(), any())).thenReturn(
            CompetitorCriterionContext.builder().criterionKey("marketPositionScore")
                .targetFacts(java.util.Map.of("markets", "EU", "marketShare", "10%"))
                .referenceFacts(java.util.Map.of("markets", "EU", "marketShare", "20%")).build()
        );
        when(evidenceService.buildContext(any(), eq("competitiveCapabilityScore"), any(), any())).thenReturn(
            CompetitorCriterionContext.builder().criterionKey("competitiveCapabilityScore")
                .targetFacts(java.util.Map.of("technologyCapabilities", "A"))
                .referenceFacts(java.util.Map.of("patents", "B")).build()
        );
        when(evidenceService.buildContext(any(), eq("strategicIntentScore"), any(), any())).thenReturn(
            CompetitorCriterionContext.builder().criterionKey("strategicIntentScore")
                .externalSignals(java.util.List.of(java.util.Map.of("category", "STRATEGIC_ANNOUNCEMENT"))).build()
        );
        when(evidenceService.buildContext(any(), eq("growthMomentumScore"), any(), any())).thenReturn(
            CompetitorCriterionContext.builder().criterionKey("growthMomentumScore")
                .periodStart(java.time.LocalDate.now().minusDays(30))
                .periodEnd(java.time.LocalDate.now())
                .externalSignals(java.util.List.of(java.util.Map.of("category", "NEWS", "title", "revenue growth 10%"))).build()
        );
        when(evidenceService.buildContext(any(), eq("competitiveThreatScore"), any(), any())).thenReturn(
            // Intentional failure for NEEDS_MORE_DATA
            CompetitorCriterionContext.builder().criterionKey("competitiveThreatScore")
                .draftEvidence(java.util.List.of()).build()
        );

        when(aiProvider.generateCriterionSuggestion(argThat(ctx -> ctx != null && "marketPositionScore".equals(ctx.getCriterionKey())), any()))
            .thenThrow(new RuntimeException("API down"));

        when(aiProvider.generateCriterionSuggestion(argThat(ctx -> ctx != null && "competitiveCapabilityScore".equals(ctx.getCriterionKey())), any()))
            .thenReturn(new CriterionSuggestionOutput() {{
                setCriterionKey("competitiveCapabilityScore");
                setSuggestedRawScore(new BigDecimal("90"));
            }});

        when(aiProvider.generateCriterionSuggestion(argThat(ctx -> ctx != null && "strategicIntentScore".equals(ctx.getCriterionKey())), any()))
            .thenReturn(new CriterionSuggestionOutput() {{
                setCriterionKey("strategicIntentScore");
                setSuggestedRawScore(new BigDecimal("80"));
            }});

        when(aiProvider.generateCriterionSuggestion(argThat(ctx -> ctx != null && "growthMomentumScore".equals(ctx.getCriterionKey())), any()))
            .thenReturn(new CriterionSuggestionOutput() {{
                setCriterionKey("growthMomentumScore");
                setSuggestedRawScore(new BigDecimal("70"));
            }});

        com.apms.domain.profile.CompanyProfileVersion v = com.apms.domain.profile.CompanyProfileVersion.builder()
            .snapshot(new HashMap<>())
            .build();
        when(evidenceService.getAndValidateVersion(any(), any(), any())).thenReturn(v);

        AutomaticSuggestion overlap = new AutomaticSuggestion();
        overlap.setCriterionKey("productMarketOverlapScore");
        overlap.setMethod(com.apms.domain.score.enums.CriterionSuggestionMethod.DETERMINISTIC);
        overlap.setReviewStatus(CriterionSuggestionReviewStatus.PENDING);
        when(comparisonService.suggestProductMarketOverlap(any(), any(), eq(com.apms.domain.score.enums.OverlapSuggestionMode.CANONICAL_STRICT))).thenReturn(overlap);

        java.util.Map<String, String> outcomes = service.generateAll(draft, new GenerateSuggestionRequest());

        // exactly six outcomes returned
        assertEquals(6, outcomes.size());

        // provider failure for one criterion does not abort others, technical failure is visible
        assertEquals("TECHNICAL_FAILURE", outcomes.get("marketPositionScore"));

        // NEEDS_MORE_DATA is visible
        assertEquals("NEEDS_MORE_DATA", outcomes.get("competitiveThreatScore"));

        // successful generation
        assertEquals("GENERATED", outcomes.get("competitiveCapabilityScore"));
        assertEquals("GENERATED", outcomes.get("strategicIntentScore"));
        assertEquals("GENERATED", outcomes.get("growthMomentumScore"));

        // overlap
        assertEquals("productMarketOverlapScore", draft.getAutomaticSuggestions().get("productMarketOverlapScore").getCriterionKey());

        // other five use AI_ASSISTED when generated
        assertEquals(com.apms.domain.score.enums.CriterionSuggestionMethod.AI_ASSISTED, draft.getAutomaticSuggestions().get("competitiveCapabilityScore").getMethod());
        assertEquals(com.apms.domain.score.enums.CriterionSuggestionMethod.AI_ASSISTED, draft.getAutomaticSuggestions().get("marketPositionScore").getMethod());

        // overlap is DETERMINISTIC
        assertEquals(com.apms.domain.score.enums.CriterionSuggestionMethod.DETERMINISTIC, draft.getAutomaticSuggestions().get("productMarketOverlapScore").getMethod());
    }
}
