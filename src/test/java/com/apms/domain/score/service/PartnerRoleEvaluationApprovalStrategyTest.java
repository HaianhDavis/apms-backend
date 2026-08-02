package com.apms.domain.score.service;

import com.apms.common.enums.SubmissionStatus;
import com.apms.common.enums.TaskStatus;
import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.company.enums.CompanyRole;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.ProjectTaskSubmission;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.domain.project.repository.sql.ProjectTaskSubmissionRepository;
import com.apms.domain.score.draft.CriterionInput;
import com.apms.domain.score.draft.EvidenceRecord;
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

import com.apms.common.security.ProjectSecurityEvaluator;
import com.apms.domain.score.registry.CanonicalRoleCriteria;

import java.util.LinkedHashMap;
import java.util.List;

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

    @Mock
    private ProjectTaskRepository taskRepository;

    @Mock
    private ProjectTaskSubmissionRepository submissionRepository;

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
        LinkedHashMap<String, List<EvidenceRecord>> evidence = new LinkedHashMap<>();
        for (String criterionKey : CanonicalRoleCriteria.PARTNER_CRITERIA) {
            CriterionInput input = new CriterionInput();
            input.setRawScore(new java.math.BigDecimal("90"));
            input.setExplanation("Staff reason for " + criterionKey);
            inputs.put(criterionKey, input);

            EvidenceRecord record = new EvidenceRecord();
            record.setEvidenceId("ev-" + criterionKey);
            record.setRawDocumentId("raw-" + criterionKey);
            evidence.put(criterionKey, List.of(record));
        }
        draft.setCriterionInputs(inputs);
        draft.setCriterionEvidence(evidence);

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
        assertEquals(SubmissionStatus.APPROVED, submission.getStatus());
        assertEquals(TaskStatus.DONE, task.getStatus());
        verify(submissionRepository).save(submission);
        verify(taskRepository).save(task);
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
        draft.getCriterionEvidence().remove(CanonicalRoleCriteria.PARTNER_CRITERIA.get(0));

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
            strategy.approve(draft, task, submission, request, 100L, "key1")
        );
        assertTrue(ex.getMessage().contains("Cannot approve evaluation"));
    }

    @Test
    void approve_AllowsBlankCommentWhenStaffConfirmedDataIsComplete() {
        when(projectSecurityEvaluator.isManager(10L)).thenReturn(true);
        when(mongoTemplate.updateFirst(any(Query.class), any(UpdateDefinition.class), eq(RoleEvaluationDraft.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

        request.setComment(" ");

        strategy.approve(draft, task, submission, request, 100L, "key1");

        verify(mongoTemplate).insert(any(RoleEvaluationVersion.class));
    }
}
