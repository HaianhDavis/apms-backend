package com.apms.domain.score.service;

import com.apms.common.exception.BusinessValidationException;
import com.apms.common.enums.SubmissionStatus;
import com.apms.common.enums.TaskStatus;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.ProjectTaskSubmission;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.domain.project.repository.sql.ProjectTaskSubmissionRepository;
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

import com.apms.domain.score.draft.EvidenceRecord;
import com.apms.domain.score.registry.CanonicalRoleCriteria;
import com.apms.domain.user.repository.sql.AccountRepository;
import com.apms.domain.audit.service.AuditLogService;

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

    @Mock
    private ProjectTaskRepository taskRepository;

    @Mock
    private ProjectTaskSubmissionRepository submissionRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private PartnerRoleEvaluationSubmissionStrategy strategy;

    @Test
    void submit_SetsStatusToInReviewAndSavesDraft() {
        RoleEvaluationDraft draft = new RoleEvaluationDraft();
        draft.setId("draft-1");
        draft.setStatus(RoleEvaluationStatus.DRAFT);
        java.util.LinkedHashMap<String, com.apms.domain.score.draft.CriterionInput> inputs = new java.util.LinkedHashMap<>();
        java.util.LinkedHashMap<String, java.util.List<EvidenceRecord>> evidence = new java.util.LinkedHashMap<>();
        for (String criterionKey : CanonicalRoleCriteria.PARTNER_CRITERIA) {
            com.apms.domain.score.draft.CriterionInput input = new com.apms.domain.score.draft.CriterionInput();
            input.setRawScore(new java.math.BigDecimal("80"));
            input.setExplanation("Staff reason for " + criterionKey);
            inputs.put(criterionKey, input);

            EvidenceRecord record = new EvidenceRecord();
            record.setEvidenceId("ev-" + criterionKey);
            record.setRawDocumentId("raw-" + criterionKey);
            evidence.put(criterionKey, java.util.List.of(record));
        }
        draft.setCriterionInputs(inputs);
        draft.setCriterionEvidence(evidence);

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
        when(accountRepository.findById(1L)).thenReturn(java.util.Optional.of(assignee));

        strategy.submit(draft, task, submission, request, 1L);

        org.mockito.ArgumentCaptor<org.springframework.data.mongodb.core.query.UpdateDefinition> updateCaptor =
                org.mockito.ArgumentCaptor.forClass(org.springframework.data.mongodb.core.query.UpdateDefinition.class);

        verify(mongoTemplate).updateFirst(any(org.springframework.data.mongodb.core.query.Query.class), updateCaptor.capture(), eq(RoleEvaluationDraft.class));

        // Assert that status was set to IN_REVIEW in the update document
        org.springframework.data.mongodb.core.query.Update update = (org.springframework.data.mongodb.core.query.Update) updateCaptor.getValue();
        org.bson.Document setDoc = (org.bson.Document) update.getUpdateObject().get("$set");
        assertEquals(RoleEvaluationStatus.IN_REVIEW, setDoc.get("status"));

        verify(mongoTemplate).insert(any(RoleEvaluationOutboxEvent.class));
        assertEquals(TaskStatus.IN_REVIEW, task.getStatus());
        assertEquals(SubmissionStatus.IN_REVIEW, submission.getStatus());
        verify(submissionRepository).save(submission);
        verify(taskRepository).save(task);
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

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
            strategy.submit(draft, task, submission, request, 1L)
        );
        assertTrue(ex.getMessage().contains("Data is INCOMPLETE"));
    }
}
