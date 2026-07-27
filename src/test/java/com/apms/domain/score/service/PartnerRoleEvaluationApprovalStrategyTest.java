package com.apms.domain.score.service;

import com.apms.domain.company.enums.CompanyRole;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.ProjectTaskSubmission;
import com.apms.domain.score.draft.CriterionInput;
import com.apms.domain.score.draft.RoleEvaluationDraft;
import com.apms.domain.score.dto.draft.ReviewRoleEvaluationRequest;
import com.apms.domain.score.enums.RoleEvaluationStatus;
import com.apms.domain.score.outbox.RoleEvaluationOutboxEvent;
import com.apms.domain.score.draft.RoleEvaluationVersion;
import com.mongodb.client.result.UpdateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.core.query.UpdateDefinition;

import com.apms.domain.score.enums.EvaluationCompletenessStatus;
import com.apms.domain.score.dto.draft.RoleEvaluationReadinessResponse;
import com.apms.common.security.ProjectSecurityEvaluator;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class PartnerRoleEvaluationApprovalStrategyTest {

    @Mock
    private MongoTemplate mongoTemplate;

    @Mock
    private PartnerDataSufficiencyEvaluator sufficiencyEvaluator;

    @Mock
    private ProjectSecurityEvaluator projectSecurityEvaluator;

    @InjectMocks
    private PartnerRoleEvaluationApprovalStrategy strategy;

    private RoleEvaluationDraft draft;
    private ProjectTask task;
    private ProjectTaskSubmission submission;
    private ReviewRoleEvaluationRequest request;

    @BeforeEach
    void setUp() {
        draft = new RoleEvaluationDraft();
        draft.setId("draft-1");
        draft.setStatus(RoleEvaluationStatus.IN_REVIEW);
        draft.setWorkingRevisionNumber(1);
        draft.setSubmittedRevisionNumber(1);
        draft.setOptimisticVersion(1L);
        draft.setEvaluatedRole(CompanyRole.PARTNER);

        LinkedHashMap<String, CriterionInput> inputs = new LinkedHashMap<>();
        for (int i = 0; i < 6; i++) {
            inputs.put("CRIT_" + i, new CriterionInput());
        }
        draft.setCriterionInputs(inputs);

        task = new ProjectTask();
        com.apms.domain.project.Project project = new com.apms.domain.project.Project();
        project.setId(10L);
        task.setProject(project);

        submission = new ProjectTaskSubmission();

        request = new ReviewRoleEvaluationRequest();
        request.setComment("Looks good");
    }

    @Test
    void supports_ReturnsTrueForPartner() {
        assertTrue(strategy.supports(CompanyRole.PARTNER));
    }

    @Test
    void approve_CreatesVersionEventAndUpdatesDraft() {
        when(mongoTemplate.updateFirst(any(Query.class), any(UpdateDefinition.class), eq(RoleEvaluationDraft.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

        when(projectSecurityEvaluator.isManager(10L)).thenReturn(true);

        RoleEvaluationReadinessResponse readiness = new RoleEvaluationReadinessResponse();
        readiness.setStaffMaySubmit(true);
        readiness.setAggregateCompletenessStatus(EvaluationCompletenessStatus.COMPLETE);
        when(sufficiencyEvaluator.evaluate(draft)).thenReturn(readiness);

        strategy.approve(draft, task, submission, request, 100L, "key1");

        ArgumentCaptor<RoleEvaluationVersion> versionCaptor = ArgumentCaptor.forClass(RoleEvaluationVersion.class);
        ArgumentCaptor<RoleEvaluationOutboxEvent> eventCaptor = ArgumentCaptor.forClass(RoleEvaluationOutboxEvent.class);
        ArgumentCaptor<UpdateDefinition> updateCaptor = ArgumentCaptor.forClass(UpdateDefinition.class);

        verify(mongoTemplate).insert(versionCaptor.capture());
        verify(mongoTemplate).insert(eventCaptor.capture());
        verify(mongoTemplate).updateFirst(any(Query.class), updateCaptor.capture(), eq(RoleEvaluationDraft.class));

        assertEquals(RoleEvaluationStatus.APPROVED, versionCaptor.getValue().getStatus());
        assertEquals("Looks good", versionCaptor.getValue().getReviewComment());

        Update update = (Update) updateCaptor.getValue();
        org.bson.Document setDoc = (org.bson.Document) update.getUpdateObject().get("$set");
        assertEquals(RoleEvaluationStatus.APPROVED, setDoc.get("status"));
    }

    @Test
    void requestRevision_CreatesEventAndUpdatesDraft() {
        when(mongoTemplate.updateFirst(any(Query.class), any(UpdateDefinition.class), eq(RoleEvaluationDraft.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

        strategy.requestRevision(draft, task, submission, request, 100L);

        ArgumentCaptor<RoleEvaluationOutboxEvent> eventCaptor = ArgumentCaptor.forClass(RoleEvaluationOutboxEvent.class);
        ArgumentCaptor<UpdateDefinition> updateCaptor = ArgumentCaptor.forClass(UpdateDefinition.class);

        verify(mongoTemplate).insert(eventCaptor.capture());
        verify(mongoTemplate).updateFirst(any(Query.class), updateCaptor.capture(), eq(RoleEvaluationDraft.class));

        Update update = (Update) updateCaptor.getValue();
        org.bson.Document setDoc = (org.bson.Document) update.getUpdateObject().get("$set");
        assertEquals(RoleEvaluationStatus.REVISION_REQUIRED, setDoc.get("status"));
        assertEquals(2, setDoc.get("workingRevisionNumber"));
    }

    @Test
    void requestRevision_ThrowsExceptionIfCommentMissing() {
        request.setComment(" ");
        assertThrows(IllegalArgumentException.class, () ->
            strategy.requestRevision(draft, task, submission, request, 100L)
        );
    }

    @Test
    void reject_ThrowsUnsupportedOperationException() {
        assertThrows(UnsupportedOperationException.class, () ->
            strategy.reject(draft, task, submission, request, 100L)
        );
    }

    @Test
    void approve_ThrowsIfUserNotManager() {
        when(projectSecurityEvaluator.isManager(10L)).thenReturn(false);
        SecurityException ex = assertThrows(SecurityException.class, () ->
            strategy.approve(draft, task, submission, request, 100L, "key1")
        );
        assertTrue(ex.getMessage().contains("User is not authorized as Manager"));
    }

    @Test
    void approve_ThrowsIfIncomplete() {
        when(projectSecurityEvaluator.isManager(10L)).thenReturn(true);
        RoleEvaluationReadinessResponse readiness = new RoleEvaluationReadinessResponse();
        readiness.setStaffMaySubmit(false);
        readiness.setAggregateCompletenessStatus(EvaluationCompletenessStatus.INCOMPLETE);
        when(sufficiencyEvaluator.evaluate(draft)).thenReturn(readiness);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
            strategy.approve(draft, task, submission, request, 100L, "key1")
        );
        assertTrue(ex.getMessage().contains("Cannot approve INCOMPLETE evaluation."));
    }

    @Test
    void approve_ThrowsIfPartialWithoutJustification() {
        when(projectSecurityEvaluator.isManager(10L)).thenReturn(true);
        RoleEvaluationReadinessResponse readiness = new RoleEvaluationReadinessResponse();
        readiness.setStaffMaySubmit(true);
        readiness.setAggregateCompletenessStatus(EvaluationCompletenessStatus.PARTIAL);
        when(sufficiencyEvaluator.evaluate(draft)).thenReturn(readiness);

        request.setComment(" ");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
            strategy.approve(draft, task, submission, request, 100L, "key1")
        );
        assertTrue(ex.getMessage().contains("Manager justification is required"));
    }
}
