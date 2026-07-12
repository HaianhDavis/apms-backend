package com.apms.domain.score.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.SubmissionStatus;
import com.apms.common.enums.SubmissionType;
import com.apms.common.enums.TaskStatus;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.company.enums.CompanyRole;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.ProjectTaskSubmission;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.domain.project.repository.sql.ProjectTaskSubmissionRepository;
import com.apms.domain.score.RoleScoreRuleSet;
import com.apms.domain.score.ScoreSnapshot;
import com.apms.domain.score.draft.CriterionInput;
import com.apms.domain.score.draft.RoleEvaluationDraft;
import com.apms.domain.score.dto.RoleEvaluationCalculationResult;
import com.apms.domain.score.dto.draft.ReviewRoleEvaluationRequest;
import com.apms.domain.score.engine.RoleScoringEngine;
import com.apms.domain.score.enums.EvaluationCompletenessStatus;
import com.apms.domain.score.enums.RoleEvaluationReviewDecision;
import com.apms.domain.score.enums.RoleEvaluationStatus;
import com.apms.domain.score.repository.mongo.RoleEvaluationDraftRepository;
import com.apms.domain.score.repository.sql.RoleScoreRuleSetRepository;
import com.apms.domain.score.repository.sql.ScoreSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RoleEvaluationApprovalService.
 *
 * <p>Key scenarios:
 * <ol>
 *   <li>Successful approval: snapshot created once, draft becomes APPROVED,
 *       task DONE, submission APPROVED.</li>
 *   <li>Idempotent recovery: if a ScoreSnapshot already exists for the draft
 *       (findBySourceEvaluationDraftId returns a value), no new snapshot is
 *       created, Mongo state is repaired.</li>
 *   <li>Request revision: draft becomes REVISION_REQUIRED, submission REJECTED
 *       (SubmissionStatus.REJECTED indicates this particular submission was
 *       not accepted; the draft carries the REVISION_REQUIRED meaning), task
 *       back to IN_PROGRESS so staff can revise.</li>
 *   <li>Rejection: draft REJECTED, active=false, activeDraftKey=null, task DONE.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class RoleEvaluationApprovalServiceTest {

    @Mock private RoleEvaluationDraftRepository draftRepository;
    @Mock private ProjectTaskRepository taskRepository;
    @Mock private ProjectTaskSubmissionRepository submissionRepository;
    @Mock private ScoreSnapshotRepository scoreSnapshotRepository;
    @Mock private CanonicalScoreSnapshotService canonicalScoreSnapshotService;
    @Mock private RoleScoringEngine scoringEngine;
    @Mock private RoleScoreRuleSetRepository ruleSetRepository;
    @Mock private AuditLogService auditLogService;

    @InjectMocks
    private RoleEvaluationApprovalService service;

    private RoleEvaluationDraft draft;
    private ProjectTask task;
    private ProjectTaskSubmission submission;

    @BeforeEach
    void setUp() {
        draft = new RoleEvaluationDraft();
        draft.setId("draft-1");
        draft.setProjectId(1L);
        draft.setTaskId(10L);
        draft.setStatus(RoleEvaluationStatus.IN_REVIEW);
        draft.setEvaluatedRole(CompanyRole.COMPETITOR);
        draft.setRuleSetVersion("v1");
        draft.setStaleTargetProfile(false);
        draft.setStaleReferenceProfile(false);
        draft.setStaleRuleSet(false);
        draft.setActive(true);
        draft.setActiveDraftKey("1:10:COMPETITOR");

        // Six required COMPETITOR criteria
        CriterionInput c1 = new CriterionInput();
        c1.setManagerConfirmed(true);
        c1.setRawScore(new BigDecimal("80"));
        c1.setEvidenceIds(List.of("ev1"));

        draft.getCriterionInputs().put("productMarketOverlapScore", c1);
        draft.getCriterionInputs().put("marketPositionScore", c1);
        draft.getCriterionInputs().put("competitiveCapabilityScore", c1);
        draft.getCriterionInputs().put("strategicIntentScore", c1);
        draft.getCriterionInputs().put("growthMomentumScore", c1);
        draft.getCriterionInputs().put("competitiveThreatScore", c1);

        task = new ProjectTask();
        task.setId(10L);
        task.setStatus(TaskStatus.IN_REVIEW);

        submission = new ProjectTaskSubmission();
        submission.setId(100L);
        submission.setProjectTask(task);
        submission.setSubmissionType(SubmissionType.ROLE_EVALUATION);
        submission.setTargetEntityId("draft-1");
        submission.setStatus(SubmissionStatus.IN_REVIEW);
    }

    // ---- Scenario 1: Successful approval (first time) ----

    /**
     * Verifies:
     * - createCanonicalSnapshot is called exactly once.
     * - Draft transitions to APPROVED with active=false, activeDraftKey=null.
     * - approvedSnapshotId is stored.
     * - Submission becomes APPROVED.
     * - Task becomes DONE.
     * - ROLE_EVALUATION_APPROVED audit event is emitted.
     */
    @Test
    void reviewDraft_Approve_Success_createsSnapshotExactlyOnce() {
        when(draftRepository.findById("draft-1")).thenReturn(Optional.of(draft));
        when(submissionRepository.findByProjectTask_Id(10L)).thenReturn(List.of(submission));
        when(taskRepository.findById(10L)).thenReturn(Optional.of(task));

        RoleScoreRuleSet ruleSet = new RoleScoreRuleSet();
        when(ruleSetRepository.findByEvaluatedRoleAndRuleSetVersion(CompanyRole.COMPETITOR, "v1"))
                .thenReturn(Optional.of(ruleSet));

        RoleEvaluationCalculationResult calcResult = RoleEvaluationCalculationResult.builder()
                .completenessStatus(EvaluationCompletenessStatus.COMPLETE)
                .build();
        when(scoringEngine.calculate(any())).thenReturn(calcResult);

        // No snapshot exists yet
        when(scoreSnapshotRepository.findBySourceEvaluationDraftId("draft-1"))
                .thenReturn(Optional.empty());

        ScoreSnapshot snapshot = new ScoreSnapshot();
        ReflectionTestUtils.setField(snapshot, "scoreSnapshotId", 555L);
        when(canonicalScoreSnapshotService.createCanonicalSnapshot(any())).thenReturn(snapshot);

        ReviewRoleEvaluationRequest req = new ReviewRoleEvaluationRequest();
        req.setDecision(RoleEvaluationReviewDecision.APPROVE);
        req.setComment("Looks good");

        service.reviewDraft("draft-1", req, 999L, "idem-key-1");

        // State transitions
        assertEquals(RoleEvaluationStatus.APPROVED, draft.getStatus());
        assertFalse(draft.getActive());
        assertNull(draft.getActiveDraftKey());
        assertEquals(555L, draft.getApprovedSnapshotId());

        assertEquals(SubmissionStatus.APPROVED, submission.getStatus());
        assertEquals(TaskStatus.DONE, task.getStatus());

        // Snapshot created exactly once
        verify(canonicalScoreSnapshotService, times(1)).createCanonicalSnapshot(any());

        // Audit
        verify(auditLogService).log(eq(999L), eq(AuditAction.ROLE_EVALUATION_APPROVED),
                any(), eq("draft-1"), any());
    }

    // ---- Scenario 2: Idempotent recovery (SQL succeeded, Mongo did not) ----

    /**
     * Verifies that when a snapshot already exists in SQL for the given draft
     * (findBySourceEvaluationDraftId returns a value), no new snapshot is
     * created and the Mongo draft state is repaired to APPROVED.
     *
     * This is the "SQL success, Mongo incomplete" recovery path:
     * - createCanonicalSnapshot must NOT be called again.
     * - Draft is repaired: APPROVED, active=false, activeDraftKey=null.
     * - approvedSnapshotId equals the existing snapshot's ID.
     * - Task becomes DONE, submission becomes APPROVED.
     */
    @Test
    void reviewDraft_Approve_IdempotentRecovery_doesNotCreateAnotherSnapshot() {
        when(draftRepository.findById("draft-1")).thenReturn(Optional.of(draft));
        when(submissionRepository.findByProjectTask_Id(10L)).thenReturn(List.of(submission));
        when(taskRepository.findById(10L)).thenReturn(Optional.of(task));

        // Simulate: SQL snapshot already exists from a prior partial run
        ScoreSnapshot existingSnapshot = new ScoreSnapshot();
        ReflectionTestUtils.setField(existingSnapshot, "scoreSnapshotId", 777L);
        when(scoreSnapshotRepository.findBySourceEvaluationDraftId("draft-1"))
                .thenReturn(Optional.of(existingSnapshot));

        ReviewRoleEvaluationRequest req = new ReviewRoleEvaluationRequest();
        req.setDecision(RoleEvaluationReviewDecision.APPROVE);
        req.setComment("Retry approval");

        service.reviewDraft("draft-1", req, 999L, "idem-key-1");

        // createCanonicalSnapshot must NOT be called for recovery
        verify(canonicalScoreSnapshotService, never()).createCanonicalSnapshot(any());

        // State is repaired
        assertEquals(RoleEvaluationStatus.APPROVED, draft.getStatus());
        assertFalse(draft.getActive());
        assertNull(draft.getActiveDraftKey());
        assertEquals(777L, draft.getApprovedSnapshotId());
        assertEquals(SubmissionStatus.APPROVED, submission.getStatus());
        assertEquals(TaskStatus.DONE, task.getStatus());
    }

    // ---- Scenario 3: Request revision ----

    /**
     * Verifies revision workflow:
     * - Draft becomes REVISION_REQUIRED (draft status carries the revision meaning).
     * - Submission becomes REJECTED (this specific submission was not accepted;
     *   staff may create a new submission after revision).
     * - Task returns to IN_PROGRESS so staff can revise.
     * - ROLE_EVALUATION_REVISION_REQUESTED audit event.
     */
    @Test
    void reviewDraft_RequestRevision_setsCorrectStates() {
        when(draftRepository.findById("draft-1")).thenReturn(Optional.of(draft));
        when(submissionRepository.findByProjectTask_Id(10L)).thenReturn(List.of(submission));
        when(taskRepository.findById(10L)).thenReturn(Optional.of(task));

        ReviewRoleEvaluationRequest req = new ReviewRoleEvaluationRequest();
        req.setDecision(RoleEvaluationReviewDecision.REQUEST_REVISION);
        req.setComment("Needs more evidence for strategic intent");

        service.reviewDraft("draft-1", req, 999L, null);

        // Draft carries REVISION_REQUIRED meaning; draft remains active for revision
        assertEquals(RoleEvaluationStatus.REVISION_REQUIRED, draft.getStatus());
        // Submission is REJECTED (this submission cycle); does not mean final rejection
        assertEquals(SubmissionStatus.REJECTED, submission.getStatus());
        // Task returns to IN_PROGRESS
        assertEquals(TaskStatus.IN_PROGRESS, task.getStatus());

        verify(auditLogService).log(eq(999L), eq(AuditAction.ROLE_EVALUATION_REVISION_REQUESTED),
                any(), eq("draft-1"), any());
    }

    // ---- Scenario 4: Rejection ----

    /**
     * Verifies final rejection:
     * - Draft becomes REJECTED with active=false, activeDraftKey=null.
     * - Submission becomes REJECTED.
     * - Task becomes DONE (evaluation is complete, with rejection outcome).
     * - ROLE_EVALUATION_REJECTED audit event.
     */
    @Test
    void reviewDraft_Reject_terminatesEvaluation() {
        when(draftRepository.findById("draft-1")).thenReturn(Optional.of(draft));
        when(submissionRepository.findByProjectTask_Id(10L)).thenReturn(List.of(submission));
        when(taskRepository.findById(10L)).thenReturn(Optional.of(task));

        ReviewRoleEvaluationRequest req = new ReviewRoleEvaluationRequest();
        req.setDecision(RoleEvaluationReviewDecision.REJECT);
        req.setComment("Company is not a genuine competitor");

        service.reviewDraft("draft-1", req, 999L, null);

        assertEquals(RoleEvaluationStatus.REJECTED, draft.getStatus());
        assertFalse(draft.getActive(), "Rejected draft must not be active");
        assertNull(draft.getActiveDraftKey(), "activeDraftKey must be cleared on rejection");
        assertEquals(SubmissionStatus.REJECTED, submission.getStatus());
        assertEquals(TaskStatus.DONE, task.getStatus());

        verify(auditLogService).log(eq(999L), eq(AuditAction.ROLE_EVALUATION_REJECTED),
                any(), eq("draft-1"), any());
    }
}
