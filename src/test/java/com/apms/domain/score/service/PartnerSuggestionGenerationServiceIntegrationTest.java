package com.apms.domain.score.service;

import com.apms.common.exception.BusinessConflictException;
import com.apms.domain.ai.dto.PartnerCriterionSuggestionResponse;
import com.apms.domain.score.draft.RoleEvaluationDraft;
import com.apms.domain.score.enums.GenerationStatus;
import com.apms.domain.score.repository.mongo.RoleEvaluationDraftRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.apms.ApmsIntegrationTestBase;

class PartnerSuggestionGenerationServiceIntegrationTest extends ApmsIntegrationTestBase {

    @Autowired
    private PartnerSuggestionGenerationService service;

    @Autowired
    private RoleEvaluationDraftRepository draftRepository;

    @Autowired
    private MongoTemplate mongoTemplate;

    @MockBean
    private PartnerEvaluationContextProvider contextProvider;

    @MockBean
    private PartnerAiSuggestionValidator validator;

    @BeforeEach
    void setUp() {
        draftRepository.deleteAll();
    }

    @Test
    void testRealMongoCAS() throws InterruptedException {
        // Persist draft revision R and optimisticVersion V
        RoleEvaluationDraft draft = new RoleEvaluationDraft();
        draft.setProjectId(100L);
        draft.setTargetCompanyId("comp-456");
        draft.setWorkingRevisionNumber(1);
        draft.setSourceSnapshotHash("hash-abc");
        draft.setPinnedSourceReferences(java.util.List.of(com.apms.domain.score.draft.ApprovedSourceReference.builder().referenceId("ref-1").build()));
        draft = draftRepository.save(draft);

        String draftId = draft.getId();
        Long initialOptimisticVersion = draft.getOptimisticVersion();
        assertEquals(0L, initialOptimisticVersion);

        // Mock provider responses
        com.apms.domain.score.dto.draft.PartnerCriterionContext context = com.apms.domain.score.dto.draft.PartnerCriterionContext.builder()
            .pinnedSources(java.util.List.of(java.util.Map.of("referenceId", "ref-1")))
            .build();
        when(contextProvider.buildContext(any(), anyString()))
            .thenReturn(context);

        PartnerCriterionSuggestionResponse aiResponse = new PartnerCriterionSuggestionResponse();
        aiResponse.setRationale("Good rationale");
        when(validator.validateAndMap(anyString(), anyString(), any()))
            .thenReturn(aiResponse);

        // Writer A
        String resultA = service.generateSuggestion(draftId, "strategicAlignmentScore", "gen-A");
        assertEquals("GENERATED", resultA);

        // Reload draft and prove writer A data is preserved
        RoleEvaluationDraft reloaded = draftRepository.findById(draftId).orElseThrow();
        assertEquals(1, reloaded.getGenerationIdempotency().get("strategicAlignmentScore").size());
        assertEquals("gen-A", reloaded.getGenerationIdempotency().get("strategicAlignmentScore").get(0).getGenerationId());
        assertEquals(GenerationStatus.APPLIED, reloaded.getGenerationIdempotency().get("strategicAlignmentScore").get(0).getValidationStatus());

        // Prove workingRevisionNumber is unchanged (still 1)
        assertEquals(1, reloaded.getWorkingRevisionNumber());

        // Prove optimisticVersion increased twice (from 0 to 1 in reserve, 1 to 2 in apply)
        assertEquals(2L, reloaded.getOptimisticVersion());

        // Writer B uses stale R/V and receives typed conflict
        // Wait, how to simulate writer B? I can use the service but first I need to reset the mock or simulate parallel.
        // Actually, since generation takes the draft ID, if we just call the service again, it reads the NEW revision, so it wouldn't be stale!
        // To make it stale, we could intercept the findById or simply do a direct update.
        // Let's simulate a concurrent update by directly modifying the DB *while* writer B is reading.

        // Simpler way: Writer A does a successful generation. Writer B is executed after, but if we want to simulate CAS conflict,
        // we can just directly modify the draft's workingRevisionNumber in the DB to simulate another writer modifying it BEFORE the apply phase!

        // Prove repository save using the reloaded document still succeeds
        reloaded.setSourceSnapshotHash("new-hash");
        draftRepository.save(reloaded);
        RoleEvaluationDraft reloadedTwice = draftRepository.findById(draftId).orElseThrow();
        assertEquals(3L, reloadedTwice.getOptimisticVersion());
        assertEquals("new-hash", reloadedTwice.getSourceSnapshotHash());
    }

    @Test
    void testConcurrentCASConflict() {
        RoleEvaluationDraft draft = new RoleEvaluationDraft();
        draft.setProjectId(100L);
        draft.setTargetCompanyId("comp-456");
        draft.setWorkingRevisionNumber(1);
        draft.setSourceSnapshotHash("hash-abc");
        draft.setPinnedSourceReferences(java.util.List.of(com.apms.domain.score.draft.ApprovedSourceReference.builder().referenceId("ref-1").build()));
        draft = draftRepository.save(draft);

        String draftId = draft.getId();

        // Mock provider responses
        com.apms.domain.score.dto.draft.PartnerCriterionContext context = com.apms.domain.score.dto.draft.PartnerCriterionContext.builder()
            .pinnedSources(java.util.List.of(java.util.Map.of("referenceId", "ref-1")))
            .build();
        when(contextProvider.buildContext(any(), anyString()))
            .thenReturn(context);

        PartnerCriterionSuggestionResponse aiResponse = new PartnerCriterionSuggestionResponse();
        aiResponse.setRationale("Good rationale");
        when(validator.validateAndMap(anyString(), anyString(), any()))
            .thenAnswer(inv -> {
                // Simulate another process modifying the draft and wiping idempotency during AI generation!
                RoleEvaluationDraft concurrent = draftRepository.findById(draftId).orElseThrow();
                concurrent.setWorkingRevisionNumber(99); // bump revision
                concurrent.setGenerationIdempotency(new java.util.LinkedHashMap<>()); // wipe metadata
                draftRepository.save(concurrent); // optimistically increments version in DB

                return aiResponse;
            });

        BusinessConflictException ex = assertThrows(BusinessConflictException.class, () ->
            service.generateSuggestion(draftId, "strategicAlignmentScore", "gen-B"));

        assertEquals("Reserved metadata lost", ex.getMessage());
    }
}
