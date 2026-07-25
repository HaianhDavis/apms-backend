package com.apms.domain.score.service;

import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.ProjectTaskSubmission;
import com.apms.domain.score.draft.RoleEvaluationDraft;
import com.apms.domain.score.dto.draft.SubmitRoleEvaluationRequest;
import com.apms.domain.score.outbox.RoleEvaluationOutboxEvent;
import com.apms.domain.score.repository.mongo.RoleEvaluationOutboxEventRepository;
import com.apms.domain.score.repository.mongo.RoleEvaluationDraftRepository;
import com.apms.domain.score.repository.mongo.RoleEvaluationVersionRepository;
import com.apms.domain.score.enums.RoleEvaluationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

import com.apms.domain.score.enums.EvaluationCompletenessStatus;
import com.apms.domain.score.dto.draft.RoleEvaluationReadinessResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class PartnerRoleEvaluationSubmissionStrategyTest {

    @Mock
    private RoleEvaluationDraftRepository draftRepository;

    @Mock
    private RoleEvaluationVersionRepository versionRepository;

    @Mock
    private PartnerDataSufficiencyEvaluator sufficiencyEvaluator;

    @Mock
    private MongoTemplate mongoTemplate;

    @Mock
    private RoleEvaluationOutboxEventRepository outboxRepository;

    @InjectMocks
    private PartnerRoleEvaluationSubmissionStrategy strategy;

    @Test
    void submit_SetsStatusToInReviewAndSavesDraft() {
        RoleEvaluationDraft draft = new RoleEvaluationDraft();
        draft.setId("draft-1");
        draft.setStatus(RoleEvaluationStatus.DRAFT);
        java.util.LinkedHashMap<String, com.apms.domain.score.draft.CriterionInput> inputs = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 6; i++) {
            inputs.put("CRIT_" + i, new com.apms.domain.score.draft.CriterionInput());
        }
        draft.setCriterionInputs(inputs);

        ProjectTask task = new ProjectTask();
        com.apms.domain.project.Project project = new com.apms.domain.project.Project();
        project.setId(10L);
        task.setProject(project);
        com.apms.domain.user.Account assignee = new com.apms.domain.user.Account();
        assignee.setId(1L);
        task.setAssignedToAccount(assignee);
        ProjectTaskSubmission submission = new ProjectTaskSubmission();
        SubmitRoleEvaluationRequest request = new SubmitRoleEvaluationRequest();

        when(mongoTemplate.updateFirst(any(org.springframework.data.mongodb.core.query.Query.class),
                any(org.springframework.data.mongodb.core.query.UpdateDefinition.class),
                eq(RoleEvaluationDraft.class)))
                .thenReturn(com.mongodb.client.result.UpdateResult.acknowledged(1, 1L, null));

        RoleEvaluationReadinessResponse readiness = new RoleEvaluationReadinessResponse();
        readiness.setStaffMaySubmit(true);
        readiness.setAggregateCompletenessStatus(EvaluationCompletenessStatus.COMPLETE);
        when(sufficiencyEvaluator.evaluate(draft)).thenReturn(readiness);

        strategy.submit(draft, task, submission, request, 1L);

        org.mockito.ArgumentCaptor<org.springframework.data.mongodb.core.query.UpdateDefinition> updateCaptor =
                org.mockito.ArgumentCaptor.forClass(org.springframework.data.mongodb.core.query.UpdateDefinition.class);

        verify(mongoTemplate).updateFirst(any(org.springframework.data.mongodb.core.query.Query.class), updateCaptor.capture(), eq(RoleEvaluationDraft.class));

        // Assert that status was set to IN_REVIEW in the update document
        org.springframework.data.mongodb.core.query.Update update = (org.springframework.data.mongodb.core.query.Update) updateCaptor.getValue();
        org.bson.Document setDoc = (org.bson.Document) update.getUpdateObject().get("$set");
        assertEquals(RoleEvaluationStatus.IN_REVIEW, setDoc.get("status"));

        verify(mongoTemplate).insert(any(RoleEvaluationOutboxEvent.class));
    }

    @Test
    void submit_ThrowsIfIncomplete() {
        RoleEvaluationDraft draft = new RoleEvaluationDraft();
        draft.setId("draft-1");

        ProjectTask task = new ProjectTask();
        com.apms.domain.project.Project project = new com.apms.domain.project.Project();
        project.setId(10L);
        task.setProject(project);
        com.apms.domain.user.Account assignee = new com.apms.domain.user.Account();
        assignee.setId(1L);
        task.setAssignedToAccount(assignee);
        ProjectTaskSubmission submission = new ProjectTaskSubmission();
        SubmitRoleEvaluationRequest request = new SubmitRoleEvaluationRequest();

        RoleEvaluationReadinessResponse readiness = new RoleEvaluationReadinessResponse();
        readiness.setStaffMaySubmit(false);
        readiness.setAggregateCompletenessStatus(EvaluationCompletenessStatus.INCOMPLETE);
        when(sufficiencyEvaluator.evaluate(draft)).thenReturn(readiness);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
            strategy.submit(draft, task, submission, request, 1L)
        );
        assertTrue(ex.getMessage().contains("Data is INCOMPLETE"));
    }
}
