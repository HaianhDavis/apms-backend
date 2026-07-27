package com.apms.domain.score.service;

import com.apms.domain.score.enums.RoleEvaluationStatus;
import com.apms.domain.score.enums.RoleEvaluationReviewDecision;
import com.apms.domain.score.draft.RoleEvaluationDraft;
import com.apms.domain.score.dto.draft.ReviewRoleEvaluationRequest;
import com.apms.domain.score.repository.mongo.RoleEvaluationDraftRepository;
import com.apms.domain.score.repository.mongo.RoleEvaluationOutboxEventRepository;
import com.apms.domain.score.repository.mongo.RoleEvaluationVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.aop.support.AopUtils;
import com.apms.common.security.ProjectSecurityEvaluator;
import com.apms.domain.score.dto.draft.RoleEvaluationReadinessResponse;
import com.apms.domain.score.enums.EvaluationCompletenessStatus;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.reset;

import com.apms.domain.score.outbox.RoleEvaluationOutboxEvent;
import com.apms.common.enums.OutboxEventStatus;
import com.apms.domain.score.draft.CriterionInput;
import com.apms.domain.project.Project;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.ProjectTaskSubmission;
import com.apms.domain.user.Account;

import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.*;

public class RoleEvaluationMongoTransactionIntegrationTest extends RoleEvaluationMongoIntegrationTestBase {

    @Autowired
    private RoleEvaluationDraftRepository draftRepository;

    @Autowired
    private RoleEvaluationOutboxEventRepository outboxEventRepository;

    @Autowired
    private RoleEvaluationVersionRepository versionRepository;

    @Autowired
    @Qualifier("partnerRoleEvaluationSubmissionStrategy")
    private RoleEvaluationSubmissionStrategy partnerSubmissionStrategy;

    @Autowired
    @Qualifier("partnerRoleEvaluationApprovalStrategy")
    private RoleEvaluationApprovalStrategy partnerApprovalStrategy;

    @Autowired
    private ProjectSecurityEvaluator projectSecurityEvaluator;

    @Autowired
    private PartnerDataSufficiencyEvaluator sufficiencyEvaluator;

    @BeforeEach
    void setUp() {
        draftRepository.deleteAll();
        outboxEventRepository.deleteAll();
        versionRepository.deleteAll();

        reset(projectSecurityEvaluator, sufficiencyEvaluator);
        when(projectSecurityEvaluator.isManager(anyLong())).thenReturn(true);

        RoleEvaluationReadinessResponse response = RoleEvaluationReadinessResponse.builder()
            .aggregateCompletenessStatus(EvaluationCompletenessStatus.COMPLETE)
            .staffMaySubmit(true)
            .build();
        when(sufficiencyEvaluator.evaluate(any(RoleEvaluationDraft.class))).thenReturn(response);
    }

    @Test
    void testStrategiesAreAopProxies() {
        assertTrue(AopUtils.isAopProxy(partnerSubmissionStrategy), "Submission strategy should be an AOP proxy");
        assertTrue(AopUtils.isAopProxy(partnerApprovalStrategy), "Approval strategy should be an AOP proxy");
    }

    private CriterionInput createCriterionInput() {
        CriterionInput input = new CriterionInput();
        return input;
    }

    private RoleEvaluationDraft createValidDraft() {
        RoleEvaluationDraft draft = new RoleEvaluationDraft();
        draft.setId("eval-123");
        draft.setStatus(RoleEvaluationStatus.DRAFT);
        draft.setWorkingRevisionNumber(1);
        draft.setSourceSnapshotHash("hash123");
        draft.setTargetProfileDocumentId("target123");

        java.util.LinkedHashMap<String, CriterionInput> inputs = new java.util.LinkedHashMap<>();
        inputs.put("c1", createCriterionInput());
        inputs.put("c2", createCriterionInput());
        inputs.put("c3", createCriterionInput());
        inputs.put("c4", createCriterionInput());
        inputs.put("c5", createCriterionInput());
        inputs.put("c6", createCriterionInput());

        draft.setCriterionInputs(inputs);
        return draftRepository.save(draft);
    }

    private ProjectTask createValidTask() {
        Account a = new Account();
        a.setId(1L);
        Project p = new Project();
        p.setId(1L);
        ProjectTask t = new ProjectTask();
        t.setId(1L);
        t.setProject(p);
        t.setAssignedToAccount(a);
        return t;
    }

    @Test
    void testDraftUpdateRollsBackWhenSubmissionOutboxInsertFails() {
        RoleEvaluationDraft draft = createValidDraft();
        ProjectTask task = createValidTask();
        ProjectTaskSubmission submission = new ProjectTaskSubmission();
        submission.setId(99L);

        String eventId = draft.getId() + "_" + draft.getWorkingRevisionNumber() + "_SUBMITTED";
        RoleEvaluationOutboxEvent blocker = new RoleEvaluationOutboxEvent();
        blocker.setEventId(eventId);
        blocker.setStatus(OutboxEventStatus.PENDING);
        outboxEventRepository.save(blocker);

        Exception e = assertThrows(Exception.class, () ->
            partnerSubmissionStrategy.submit(draft, task, submission, null, 1L));
        System.out.println("CAUGHT EXCEPTION IN TEST 1: " + e.getClass().getName());
        e.printStackTrace();

        RoleEvaluationDraft reloaded = draftRepository.findById(draft.getId()).orElseThrow();
        assertEquals(RoleEvaluationStatus.DRAFT, reloaded.getStatus());
        assertNull(reloaded.getSubmittedRevisionNumber());
    }

    @Test
    void testVersionInsertRollsBackWhenPointerCasFails() {
        RoleEvaluationDraft draft = createValidDraft();
        draft.setStatus(RoleEvaluationStatus.IN_REVIEW);
        draft.setSubmittedRevisionNumber(1);
        draft.setSubmittedSourceSnapshotHash(draft.getSourceSnapshotHash());
        draftRepository.save(draft);

        ProjectTask task = createValidTask();

        // Cause CAS failure by updating the draft's version manually before the approval process saves it
        RoleEvaluationDraft modifiedDraft = draftRepository.findById(draft.getId()).orElseThrow();
        modifiedDraft.setStatus(RoleEvaluationStatus.REJECTED);
        draftRepository.save(modifiedDraft); // increments @Version
        ReviewRoleEvaluationRequest request = new ReviewRoleEvaluationRequest();
        request.setDecision(RoleEvaluationReviewDecision.APPROVE);
        request.setComment("notes");

        assertThrows(IllegalStateException.class, () ->
            partnerApprovalStrategy.approve(draft, task, new ProjectTaskSubmission(), request, 1L, "notes"));

        // Assert Rollback
        RoleEvaluationDraft reloaded = draftRepository.findById(draft.getId()).orElseThrow();
        assertEquals(RoleEvaluationStatus.REJECTED, reloaded.getStatus()); // remains as we set it, not APPROVED
        assertEquals(0, versionRepository.count());
        assertEquals(0, outboxEventRepository.count());
    }

    @Test
    void testVersionAndPointerRollBackWhenApprovalOutboxInsertFails() {
        RoleEvaluationDraft draft = createValidDraft();
        draft.setStatus(RoleEvaluationStatus.IN_REVIEW);
        draft.setSubmittedRevisionNumber(1);
        draft.setSubmittedSourceSnapshotHash(draft.getSourceSnapshotHash());
        draftRepository.save(draft);

        ProjectTask task = createValidTask();
        task.setId(99L);

        // Pre-insert to cause DuplicateKeyException
        Integer nextVersion = (draft.getCurrentApprovedVersionNumber() == null ? 0 : draft.getCurrentApprovedVersionNumber()) + 1;
        String eventId = draft.getId() + "_" + nextVersion + "_APPROVED";
        RoleEvaluationOutboxEvent blocker = new RoleEvaluationOutboxEvent();
        blocker.setEventId(eventId);
        blocker.setStatus(OutboxEventStatus.PENDING);
        outboxEventRepository.save(blocker);

        ReviewRoleEvaluationRequest request = new ReviewRoleEvaluationRequest();
        request.setDecision(RoleEvaluationReviewDecision.APPROVE);
        request.setComment("notes");

        Exception e = assertThrows(Exception.class, () ->
            partnerApprovalStrategy.approve(draft, task, new ProjectTaskSubmission(), request, 1L, "notes"));
        System.out.println("CAUGHT EXCEPTION IN TEST 3: " + e.getClass().getName());
        e.printStackTrace();

        RoleEvaluationDraft reloaded = draftRepository.findById(draft.getId()).orElseThrow();
        assertEquals(RoleEvaluationStatus.IN_REVIEW, reloaded.getStatus());
        assertEquals(0, versionRepository.count());
        // Only the blocker event should exist
        assertEquals(1, outboxEventRepository.count());
    }

    @Test
    void testRevisionRequestRollsBackWhenOutboxInsertFails() {
        RoleEvaluationDraft draft = createValidDraft();
        draft.setStatus(RoleEvaluationStatus.IN_REVIEW);
        draft.setSubmittedRevisionNumber(1);
        draft.setSubmittedSourceSnapshotHash(draft.getSourceSnapshotHash());
        draftRepository.save(draft);

        ProjectTask task = createValidTask();
        task.setId(99L);

        Integer nextRevision = draft.getWorkingRevisionNumber() + 1;
        String eventId = draft.getId() + "_" + draft.getWorkingRevisionNumber() + "_" + nextRevision + "_REVISION_REQUESTED";
        RoleEvaluationOutboxEvent blocker = new RoleEvaluationOutboxEvent();
        blocker.setEventId(eventId);
        blocker.setStatus(OutboxEventStatus.PENDING);
        outboxEventRepository.save(blocker);

        ReviewRoleEvaluationRequest request = new ReviewRoleEvaluationRequest();
        request.setDecision(RoleEvaluationReviewDecision.REQUEST_REVISION);
        request.setComment("notes");

        Exception e = assertThrows(Exception.class, () ->
            partnerApprovalStrategy.requestRevision(draft, task, new ProjectTaskSubmission(), request, 1L));
        System.out.println("CAUGHT EXCEPTION IN TEST 4: " + e.getClass().getName());
        e.printStackTrace();

        RoleEvaluationDraft reloaded = draftRepository.findById(draft.getId()).orElseThrow();
        assertEquals(RoleEvaluationStatus.IN_REVIEW, reloaded.getStatus());
        assertNull(reloaded.getReviewComment());
        assertEquals(1, outboxEventRepository.count());
    }
}
