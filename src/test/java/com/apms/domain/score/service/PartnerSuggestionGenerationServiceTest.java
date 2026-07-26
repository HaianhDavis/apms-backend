package com.apms.domain.score.service;

import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.ai.dto.PartnerCriterionSuggestionResponse;
import com.apms.domain.score.draft.ApprovedSourceReference;
import com.apms.domain.score.draft.AutomaticSuggestion;
import com.apms.domain.score.draft.RoleEvaluationDraft;
import com.apms.domain.score.draft.PartnerSuggestionGenerationMetadata;
import com.apms.domain.score.enums.GenerationStatus;
import com.apms.domain.score.dto.draft.PartnerCriterionContext;
import com.apms.domain.score.repository.mongo.RoleEvaluationDraftRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PartnerSuggestionGenerationServiceTest {

    private RoleEvaluationDraftRepository draftRepository;
    private PartnerEvaluationContextProvider contextProvider;
    private PartnerAiSuggestionValidator validator;
    private com.apms.domain.ai.service.provider.PartnerAiPromptProvider promptProvider;
    private com.apms.domain.ai.service.provider.PartnerCriterionSuggestionProvider aiProvider;
    private PartnerDataSufficiencyEvaluator sufficiencyEvaluator;
    private PartnerSuggestionGenerationService service;

    @BeforeEach
    void setUp() {
        draftRepository = Mockito.mock(RoleEvaluationDraftRepository.class);
        contextProvider = Mockito.mock(PartnerEvaluationContextProvider.class);
        validator = Mockito.mock(PartnerAiSuggestionValidator.class);
        promptProvider = Mockito.mock(com.apms.domain.ai.service.provider.PartnerAiPromptProvider.class);
        aiProvider = Mockito.mock(com.apms.domain.ai.service.provider.PartnerCriterionSuggestionProvider.class);
        sufficiencyEvaluator = Mockito.mock(PartnerDataSufficiencyEvaluator.class);

        org.springframework.data.mongodb.core.MongoTemplate mongoTemplate = Mockito.mock(org.springframework.data.mongodb.core.MongoTemplate.class);
        Mockito.when(mongoTemplate.updateFirst(Mockito.any(), Mockito.any(), Mockito.eq(RoleEvaluationDraft.class)))
                .thenReturn(com.mongodb.client.result.UpdateResult.acknowledged(1, 1L, null));
        // Default AI responses
        Mockito.when(promptProvider.getPromptTemplate(anyString())).thenReturn("prompt");
        Mockito.when(aiProvider.generateSuggestionJson(any(), anyString())).thenReturn("{}");

        // Default readiness
        com.apms.domain.score.dto.draft.RoleEvaluationReadinessResponse readiness = com.apms.domain.score.dto.draft.RoleEvaluationReadinessResponse.builder()
            .criterionResults(java.util.Map.of(
                "crit1", com.apms.domain.score.dto.draft.CriterionReadinessResult.builder()
                    .sufficiencyStatus(PartnerDataSufficiencyEvaluator.SufficiencyStatus.COMPLETE)
                    .build()
            ))
            .build();
        Mockito.when(sufficiencyEvaluator.evaluate(any())).thenReturn(readiness);

        service = new PartnerSuggestionGenerationService(draftRepository, contextProvider, validator, mongoTemplate, promptProvider, aiProvider, sufficiencyEvaluator, null);
    }

    @Test
    void testValidProviderResponse() {
        RoleEvaluationDraft draft = new RoleEvaluationDraft();
        draft.setId("draft1");
        draft.setWorkingRevisionNumber(5);
        draft.setSourceSnapshotHash("hash1");
        draft.setPinnedSourceReferences(List.of(new ApprovedSourceReference()));

        when(draftRepository.findById("draft1")).thenReturn(Optional.of(draft));

        PartnerCriterionContext ctx = PartnerCriterionContext.builder().build();
        when(contextProvider.buildContext(draft, "crit1")).thenReturn(ctx);

        PartnerCriterionSuggestionResponse aiResp = new PartnerCriterionSuggestionResponse();
        aiResp.setRationale("Good rationale");
        when(promptProvider.getPromptTemplate("crit1")).thenReturn("prompt");
        when(aiProvider.generateSuggestionJson(any(), anyString())).thenReturn("{}");
        when(validator.validateAndMap(anyString(), eq("crit1"), eq(draft.getPinnedSourceReferences()))).thenReturn(aiResp);

        String result = service.generateSuggestion("draft1", "crit1", "gen1");
        assertEquals("GENERATED", result);

        verify(draftRepository, never()).save(any(RoleEvaluationDraft.class));
    }

    @Test
    void testProviderFailureDoesNotMutateDraft() {
        RoleEvaluationDraft draft = new RoleEvaluationDraft();
        draft.setId("draft1");
        draft.setPinnedSourceReferences(List.of(new ApprovedSourceReference()));

        when(draftRepository.findById("draft1")).thenReturn(Optional.of(draft));
        when(contextProvider.buildContext(draft, "crit1")).thenThrow(new RuntimeException("Provider failed"));

        assertThrows(RuntimeException.class, () -> service.generateSuggestion("draft1", "crit1", "gen1"));
        verify(draftRepository, never()).save(any());
    }

    @Test
    void testValidationFailure() {
        RoleEvaluationDraft draft = new RoleEvaluationDraft();
        draft.setId("draft1");
        draft.setSourceSnapshotHash("hashX"); // Set hash
        draft.setPinnedSourceReferences(List.of(new ApprovedSourceReference()));

        when(draftRepository.findById("draft1")).thenReturn(Optional.of(draft));
        when(contextProvider.buildContext(draft, "crit1")).thenReturn(PartnerCriterionContext.builder().build());

        when(validator.validateAndMap(anyString(), eq("crit1"), any())).thenThrow(new BusinessValidationException("Validation failed"));

        assertThrows(BusinessValidationException.class, () -> service.generateSuggestion("draft1", "crit1", "gen1"));
        verify(draftRepository, never()).save(any());
    }

    @Test
    void testIdempotencySameSnapshot() {
        RoleEvaluationDraft draft = new RoleEvaluationDraft();
        draft.setId("draft1");
        draft.setWorkingRevisionNumber(5);
        draft.setSourceSnapshotHash("hashX");
        draft.setPinnedSourceReferences(List.of(new ApprovedSourceReference()));

        PartnerSuggestionGenerationMetadata existing = new PartnerSuggestionGenerationMetadata();
        existing.setGenerationId("gen1");
        existing.setDraftRevisionNumber(5);
        existing.setSourceSnapshotHash("hashX");
        existing.setValidationStatus(GenerationStatus.APPLIED);

        List<PartnerSuggestionGenerationMetadata> metas = new ArrayList<>();
        metas.add(existing);
        draft.getGenerationIdempotency().put("crit1", metas);

        when(draftRepository.findById("draft1")).thenReturn(Optional.of(draft));
        when(contextProvider.buildContext(draft, "crit1")).thenReturn(PartnerCriterionContext.builder().build());
        when(validator.validateAndMap(any(), any(), any())).thenReturn(new PartnerCriterionSuggestionResponse());

        String result = service.generateSuggestion("draft1", "crit1", "gen1");
        assertEquals("ALREADY_GENERATED", result);
        verifyNoInteractions(contextProvider);
    }

    @Test
    void testIdempotencyDifferentSnapshotConflict() {
        RoleEvaluationDraft draft = new RoleEvaluationDraft();
        draft.setId("draft1");
        draft.setWorkingRevisionNumber(6); // Changed
        draft.setSourceSnapshotHash("hashX");
        draft.setPinnedSourceReferences(List.of(new ApprovedSourceReference()));

        PartnerSuggestionGenerationMetadata existing = new PartnerSuggestionGenerationMetadata();
        existing.setGenerationId("gen1");
        existing.setDraftRevisionNumber(5); // Old
        existing.setSourceSnapshotHash("hashY"); // Differs
        existing.setValidationStatus(GenerationStatus.APPLIED);

        List<PartnerSuggestionGenerationMetadata> metas = new ArrayList<>();
        metas.add(existing);
        draft.getGenerationIdempotency().put("crit1", metas);

        when(draftRepository.findById("draft1")).thenReturn(Optional.of(draft));

        assertThrows(com.apms.common.exception.BusinessConflictException.class, () ->
            service.generateSuggestion("draft1", "crit1", "gen1"));
    }

    @Test
    void testDraftRevisionChanged() {
        RoleEvaluationDraft draft = new RoleEvaluationDraft();
        draft.setId("draft1");
        draft.setWorkingRevisionNumber(5);
        draft.setSourceSnapshotHash("hash1");
        draft.setPinnedSourceReferences(List.of(new ApprovedSourceReference()));

        RoleEvaluationDraft reloadedDraft = new RoleEvaluationDraft();
        reloadedDraft.setId("draft1");
        reloadedDraft.setWorkingRevisionNumber(6); // state changed during AI call
        reloadedDraft.setSourceSnapshotHash("hash1");
        reloadedDraft.setPinnedSourceReferences(List.of(new ApprovedSourceReference()));

        when(draftRepository.findById("draft1"))
            .thenReturn(Optional.of(draft))
            .thenAnswer(inv -> {
                reloadedDraft.setGenerationIdempotency(draft.getGenerationIdempotency());
                return Optional.of(reloadedDraft);
            });
        when(contextProvider.buildContext(draft, "crit1")).thenReturn(PartnerCriterionContext.builder().build());

        PartnerCriterionSuggestionResponse aiResp = new PartnerCriterionSuggestionResponse();
        aiResp.setRationale("Good");
        when(validator.validateAndMap(anyString(), eq("crit1"), any())).thenReturn(aiResp);

        String result = service.generateSuggestion("draft1", "crit1", "gen2");
        assertEquals("STALE_GENERATION", result);
    }

    @Test
    void testSourceSnapshotHashChanged() {
        RoleEvaluationDraft draft = new RoleEvaluationDraft();
        draft.setId("draft1");
        draft.setWorkingRevisionNumber(5);
        draft.setSourceSnapshotHash("hash1");
        draft.setPinnedSourceReferences(List.of(new ApprovedSourceReference()));

        RoleEvaluationDraft reloadedDraft = new RoleEvaluationDraft();
        reloadedDraft.setId("draft1");
        reloadedDraft.setWorkingRevisionNumber(5);
        reloadedDraft.setSourceSnapshotHash("hash2"); // hash changed
        reloadedDraft.setPinnedSourceReferences(List.of(new ApprovedSourceReference()));

        when(draftRepository.findById("draft1"))
            .thenReturn(Optional.of(draft))
            .thenAnswer(inv -> {
                reloadedDraft.setGenerationIdempotency(draft.getGenerationIdempotency());
                return Optional.of(reloadedDraft);
            });
        when(contextProvider.buildContext(draft, "crit1")).thenReturn(PartnerCriterionContext.builder().build());

        PartnerCriterionSuggestionResponse aiResp = new PartnerCriterionSuggestionResponse();
        aiResp.setRationale("Good");
        when(validator.validateAndMap(anyString(), eq("crit1"), any())).thenReturn(aiResp);

        String result = service.generateSuggestion("draft1", "crit1", "gen2");
        assertEquals("STALE_GENERATION", result);
    }

    @Test
    void testSourceSetOrderNotStale() {
        RoleEvaluationDraft draft = new RoleEvaluationDraft();
        draft.setId("draft1");
        draft.setWorkingRevisionNumber(5);
        draft.setSourceSnapshotHash("hash-AB");
        ApprovedSourceReference refA = ApprovedSourceReference.builder().referenceId("ref-A").build();
        ApprovedSourceReference refB = ApprovedSourceReference.builder().referenceId("ref-B").build();
        draft.setPinnedSourceReferences(List.of(refA, refB));

        RoleEvaluationDraft reloadedDraft = new RoleEvaluationDraft();
        reloadedDraft.setId("draft1");
        reloadedDraft.setWorkingRevisionNumber(5);
        reloadedDraft.setSourceSnapshotHash("hash-AB");
        reloadedDraft.setPinnedSourceReferences(List.of(refB, refA)); // Order swapped

        when(draftRepository.findById("draft1"))
            .thenReturn(Optional.of(draft))
            .thenAnswer(inv -> {
                reloadedDraft.setGenerationIdempotency(draft.getGenerationIdempotency());
                return Optional.of(reloadedDraft);
            });
        when(contextProvider.buildContext(draft, "crit1")).thenReturn(PartnerCriterionContext.builder().build());

        PartnerCriterionSuggestionResponse aiResp = new PartnerCriterionSuggestionResponse();
        aiResp.setRationale("Good");
        when(validator.validateAndMap(anyString(), eq("crit1"), any())).thenReturn(aiResp);

        String result = service.generateSuggestion("draft1", "crit1", "gen2");
        assertEquals("GENERATED", result); // not stale
    }

    @Test
    void testSourceSetMembershipStale() {
        RoleEvaluationDraft draft = new RoleEvaluationDraft();
        draft.setId("draft1");
        draft.setWorkingRevisionNumber(5);
        draft.setSourceSnapshotHash("hash-AB");
        ApprovedSourceReference refA = ApprovedSourceReference.builder().referenceId("ref-A").build();
        ApprovedSourceReference refB = ApprovedSourceReference.builder().referenceId("ref-B").build();
        draft.setPinnedSourceReferences(List.of(refA, refB));

        AutomaticSuggestion oldSuggestion = new AutomaticSuggestion();
        oldSuggestion.setSuggestionRationale("Old Rationale");
        draft.getAutomaticSuggestions().put("crit1", oldSuggestion);

        RoleEvaluationDraft reloadedDraft = new RoleEvaluationDraft();
        reloadedDraft.setId("draft1");
        reloadedDraft.setWorkingRevisionNumber(5);
        reloadedDraft.setSourceSnapshotHash("hash-AC"); // Hash changed
        ApprovedSourceReference refC = ApprovedSourceReference.builder().referenceId("ref-C").build();
        reloadedDraft.setPinnedSourceReferences(List.of(refA, refC));
        reloadedDraft.getAutomaticSuggestions().put("crit1", oldSuggestion);

        when(draftRepository.findById("draft1"))
            .thenReturn(Optional.of(draft))
            .thenAnswer(inv -> {
                reloadedDraft.setGenerationIdempotency(draft.getGenerationIdempotency());
                return Optional.of(reloadedDraft);
            });
        when(contextProvider.buildContext(draft, "crit1")).thenReturn(PartnerCriterionContext.builder().build());

        PartnerCriterionSuggestionResponse aiResp = new PartnerCriterionSuggestionResponse();
        aiResp.setRationale("New AI Rationale");
        when(promptProvider.getPromptTemplate("crit1")).thenReturn("prompt");
        when(aiProvider.generateSuggestionJson(any(), anyString())).thenReturn("{}");
        when(validator.validateAndMap(anyString(), eq("crit1"), any())).thenReturn(aiResp);

        String result = service.generateSuggestion("draft1", "crit1", "gen2");
        assertEquals("STALE_GENERATION", result);

        assertEquals("Old Rationale", reloadedDraft.getAutomaticSuggestions().get("crit1").getSuggestionRationale());
        assertEquals(GenerationStatus.STALE, reloadedDraft.getGenerationIdempotency().get("crit1").stream()
            .filter(m -> "gen2".equals(m.getGenerationId())).findFirst().get().getValidationStatus());
    }

    @Test
    void testInsufficientDataBlocksGeneration() {
        RoleEvaluationDraft draft = new RoleEvaluationDraft();
        draft.setId("draft1");
        draft.setWorkingRevisionNumber(5);
        draft.setSourceSnapshotHash("hash1");
        draft.setPinnedSourceReferences(List.of(new ApprovedSourceReference()));

        when(draftRepository.findById("draft1")).thenReturn(Optional.of(draft));

        com.apms.domain.score.dto.draft.RoleEvaluationReadinessResponse readiness = com.apms.domain.score.dto.draft.RoleEvaluationReadinessResponse.builder()
            .criterionResults(java.util.Map.of(
                "crit1", com.apms.domain.score.dto.draft.CriterionReadinessResult.builder()
                    .sufficiencyStatus(PartnerDataSufficiencyEvaluator.SufficiencyStatus.INCOMPLETE)
                    .build()
            ))
            .build();
        when(sufficiencyEvaluator.evaluate(draft)).thenReturn(readiness);

        String result = service.generateSuggestion("draft1", "crit1", "gen1");
        assertEquals("NEEDS_MORE_DATA", result);

        verify(aiProvider, never()).generateSuggestionJson(any(), anyString());
    }
}
