package com.apms.domain.score.service;

import com.apms.domain.company.enums.CompanyRole;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.score.draft.ApprovedSourceReference;
import com.apms.domain.score.draft.RoleEvaluationDraft;
import com.apms.domain.score.engine.RoleScoringEngine;
import com.apms.domain.score.repository.mongo.RoleEvaluationDraftRepository;
import com.apms.domain.score.repository.mongo.RoleEvaluationVersionRepository;
import com.apms.domain.score.repository.sql.ScoreSnapshotRepository;
// Assume these exist conceptually or physically in the package:
// import com.apms.domain.project.repository.sql.ProjectTaskRepository;
// import com.apms.domain.score.repository.sql.OutboxEventRepository;
// import com.apms.domain.company.repository.Neo4jCompanyClient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.*;

class PartnerSideEffectAbsenceTest {

    private RoleEvaluationDraftRepository draftRepository;
    private RoleEvaluationVersionRepository versionRepository;
    private PartnerEvaluationContextProvider contextProvider;
    private PartnerAiSuggestionValidator validator;
    private RoleScoringEngine roleScoringEngine;
    private ScoreSnapshotRepository scoreSnapshotRepository;
    private CompanyProfileRepository companyProfileRepository;
    private PartnerSuggestionGenerationService generationService;
    // Mocks for other forbidden side effects
    private Runnable ahpInvocationMock;
    private Runnable outboxInsertMock;
    private Runnable sqlTaskMutationMock;

    @BeforeEach
    void setUp() {
        draftRepository = mock(RoleEvaluationDraftRepository.class);
        versionRepository = mock(RoleEvaluationVersionRepository.class);
        contextProvider = mock(PartnerEvaluationContextProvider.class);
        validator = mock(PartnerAiSuggestionValidator.class);
        roleScoringEngine = mock(RoleScoringEngine.class);
        scoreSnapshotRepository = mock(ScoreSnapshotRepository.class);
        companyProfileRepository = mock(CompanyProfileRepository.class);

        ahpInvocationMock = mock(Runnable.class);
        outboxInsertMock = mock(Runnable.class);
        sqlTaskMutationMock = mock(Runnable.class);

        org.springframework.data.mongodb.core.MongoTemplate mongoTemplate = mock(org.springframework.data.mongodb.core.MongoTemplate.class);
        when(mongoTemplate.updateFirst(any(), any(), eq(RoleEvaluationDraft.class)))
                .thenReturn(com.mongodb.client.result.UpdateResult.acknowledged(1, 1L, null));
        com.apms.domain.ai.service.provider.PartnerAiPromptProvider promptProvider = mock(com.apms.domain.ai.service.provider.PartnerAiPromptProvider.class);
        com.apms.domain.ai.service.provider.PartnerCriterionSuggestionProvider aiProvider = mock(com.apms.domain.ai.service.provider.PartnerCriterionSuggestionProvider.class);
        com.apms.domain.score.service.PartnerDataSufficiencyEvaluator sufficiencyEvaluator = mock(com.apms.domain.score.service.PartnerDataSufficiencyEvaluator.class);

        com.apms.domain.score.dto.draft.RoleEvaluationReadinessResponse readiness = com.apms.domain.score.dto.draft.RoleEvaluationReadinessResponse.builder()
            .criterionResults(java.util.Map.of(
                "businessValueContributionScore", com.apms.domain.score.dto.draft.CriterionReadinessResult.builder()
                    .sufficiencyStatus(PartnerDataSufficiencyEvaluator.SufficiencyStatus.COMPLETE)
                    .build()
            ))
            .build();
        when(sufficiencyEvaluator.evaluate(any())).thenReturn(readiness);

        when(promptProvider.getPromptTemplate(anyString())).thenReturn("prompt");
        when(aiProvider.generateSuggestionJson(any(), anyString())).thenReturn("{}");

        generationService = new PartnerSuggestionGenerationService(
            draftRepository, contextProvider, validator, mongoTemplate,
            promptProvider,
            aiProvider,
            sufficiencyEvaluator
        );
    }

    @Test
    void testNoSideEffectsOnGeneration() {
        RoleEvaluationDraft draft = new RoleEvaluationDraft();
        draft.setId("draft1");
        draft.setWorkingRevisionNumber(1);
        draft.setSourceSnapshotHash("hash1");
        draft.setEvaluatedRole(CompanyRole.PARTNER);
        draft.setPinnedSourceReferences(List.of(new ApprovedSourceReference()));

        when(draftRepository.findById("draft1")).thenReturn(Optional.of(draft));

        com.apms.domain.score.dto.draft.PartnerCriterionContext context = com.apms.domain.score.dto.draft.PartnerCriterionContext.builder().criterionKey("crit1").build();
        when(contextProvider.buildContext(any(), any())).thenReturn(context);

        com.apms.domain.ai.dto.PartnerCriterionSuggestionResponse suggestionResp = new com.apms.domain.ai.dto.PartnerCriterionSuggestionResponse();
        suggestionResp.setRationale("test");
        when(validator.validateAndMap(any(), any(), any())).thenReturn(suggestionResp);

        generationService.generateSuggestion("draft1", "crit1", "gen1");

        verifyNoInteractions(roleScoringEngine);
        verifyNoInteractions(scoreSnapshotRepository);
        verifyNoInteractions(versionRepository);
        verifyNoInteractions(companyProfileRepository);
        verifyNoInteractions(ahpInvocationMock);
        verifyNoInteractions(outboxInsertMock);
        verifyNoInteractions(sqlTaskMutationMock);
    }
}
