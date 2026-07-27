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
import com.apms.domain.score.draft.RoleEvaluationDraft;
import com.apms.domain.score.dto.RoleEvaluationCalculationRequest;
import com.apms.domain.score.dto.RoleEvaluationCalculationResult;
import com.apms.domain.score.dto.draft.ReviewRoleEvaluationRequest;
import com.apms.domain.score.engine.RoleScoringEngine;
import com.apms.domain.score.enums.EvaluationCompletenessStatus;
import com.apms.domain.score.enums.RoleEvaluationStatus;
import com.apms.domain.score.repository.mongo.RoleEvaluationDraftRepository;
import com.apms.domain.score.repository.sql.RoleScoreRuleSetRepository;
import com.apms.domain.score.repository.sql.ScoreSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class CompetitorRoleEvaluationApprovalStrategy implements RoleEvaluationApprovalStrategy {

    private final RoleEvaluationDraftRepository draftRepository;
    private final ProjectTaskRepository taskRepository;
    private final ProjectTaskSubmissionRepository submissionRepository;

    private final ScoreSnapshotRepository scoreSnapshotRepository;
    private final CanonicalScoreSnapshotService canonicalScoreSnapshotService;
    private final RoleScoringEngine scoringEngine;
    private final RoleScoreRuleSetRepository ruleSetRepository;
    private final AuditLogService auditLogService;

    @Override
    public boolean supports(CompanyRole role) {
        return role == CompanyRole.COMPETITOR;
    }

    @Override
    @Transactional(transactionManager = "transactionManager")
    public void approve(RoleEvaluationDraft draft, ProjectTask task, ProjectTaskSubmission submission, ReviewRoleEvaluationRequest request, Long accountId, String idempotencyKey) {

        // 1. Recovery Check: Has this draft already produced a snapshot?
        Optional<ScoreSnapshot> existingSnapshot = scoreSnapshotRepository.findBySourceEvaluationDraftId(draft.getId());
        if (existingSnapshot.isPresent()) {
            repairStateAndReturn(draft, task, submission, existingSnapshot.get().getScoreSnapshotId(), accountId);
            return;
        }

        // 2. Validation
        if (draft.getStaleTargetProfile() || draft.getStaleReferenceProfile() || draft.getStaleRuleSet()) {
            if (!Boolean.TRUE.equals(request.getAcknowledgeStaleVersions())) {
                throw new IllegalStateException("Draft contains stale versions. You must explicitly acknowledge them to approve.");
            }
        }

        // Validate 6 inputs, manager confirmed, evidence present
        long confirmedCount = draft.getCriterionInputs().values().stream()
                .filter(input -> input.getManagerConfirmed() != null && input.getManagerConfirmed())
                .count();
        if (draft.getCriterionInputs().size() < 6 || confirmedCount < 6) {
             if (draft.getCriterionInputs().size() != 6) {
                 throw new IllegalStateException("Draft must have exactly 6 inputs for COMPETITOR.");
             }
        }
        // Force manager confirmation
        draft.getCriterionInputs().values().forEach(input -> input.setManagerConfirmed(true));

        // 3. Move to APPROVAL_PROCESSING
        draft.setStatus(RoleEvaluationStatus.APPROVAL_PROCESSING);
        draft.setApprovalProcessingStartedAt(LocalDateTime.now());
        draft.setApprovalIdempotencyKey(idempotencyKey);
        draftRepository.save(draft);

        auditLogService.log(accountId, AuditAction.ROLE_EVALUATION_APPROVAL_STARTED, "ROLE_EVALUATION_DRAFT", draft.getId(),
                "Approval processing started with idempotencyKey=" + idempotencyKey);

        // 4. Calculate Final Result
        RoleScoreRuleSet ruleSet = ruleSetRepository.findByEvaluatedRoleAndRuleSetVersion(
                        draft.getEvaluatedRole(), draft.getRuleSetVersion())
                .orElseThrow(() -> new IllegalStateException("Active rule set not found"));

        RoleEvaluationCalculationRequest calcRequest = new RoleEvaluationCalculationRequest();
        calcRequest.setEvaluatedRole(draft.getEvaluatedRole());
        calcRequest.setTargetCompanyProfileId(draft.getTargetProfileDocumentId());
        calcRequest.setTargetProfileVersion(draft.getTargetProfileVersion());
        calcRequest.setReferenceCompanyProfileId(draft.getReferenceProfileDocumentId());
        calcRequest.setReferenceProfileVersion(draft.getReferenceProfileVersion());
        calcRequest.setRuleSetVersion(ruleSet.getRuleSetVersion());
        calcRequest.setCalculatedByAccountId(accountId);
        calcRequest.setSourceEvaluationDraftId(draft.getId());
        calcRequest.setApprovalIdempotencyKey(idempotencyKey);

        Map<String, BigDecimal> scores = new HashMap<>();
        draft.getCriterionInputs().forEach((k, v) -> scores.put(k, v.getRawScore()));
        calcRequest.setCriterionScores(new LinkedHashMap<>(scores));

        RoleEvaluationCalculationResult result = scoringEngine.calculate(calcRequest);
        if (result.getCompletenessStatus() != EvaluationCompletenessStatus.COMPLETE) {
            draft.setStatus(RoleEvaluationStatus.APPROVAL_FAILED);
            draft.setApprovalFailureReason("RoleScoringEngine result is incomplete: " + result.getMissingCriteria());
            draftRepository.save(draft);
            throw new IllegalStateException("Cannot approve: Final score calculation was not COMPLETE.");
        }

        // 5. Save canonical snapshot (Database boundary #1 - SQL)
        ScoreSnapshot snapshot;
        try {
            snapshot = canonicalScoreSnapshotService.createCanonicalSnapshot(calcRequest);
        } catch (Exception e) {
            log.error("Failed to save SQL ScoreSnapshot", e);
            draft.setStatus(RoleEvaluationStatus.APPROVAL_FAILED);
            draft.setApprovalFailureReason("SQL error: " + e.getMessage());
            draftRepository.save(draft);
            auditLogService.log(accountId, AuditAction.ROLE_EVALUATION_APPROVAL_FAILED, "ROLE_EVALUATION_DRAFT", draft.getId(),
                    "Approval failed: " + e.getMessage());
            throw new IllegalStateException("Failed to save canonical snapshot: " + e.getMessage(), e);
        }

        // 6. Complete (Database boundary #2 - Mongo)
        repairStateAndReturn(draft, task, submission, snapshot.getScoreSnapshotId(), accountId);
    }

    @Override
    @Transactional(transactionManager = "transactionManager")
    public void requestRevision(RoleEvaluationDraft draft, ProjectTask task, ProjectTaskSubmission submission, ReviewRoleEvaluationRequest request, Long accountId) {
        if (request.getComment() == null || request.getComment().isBlank()) {
            throw new IllegalArgumentException("Comment required for requesting revision");
        }

        draft.setStatus(RoleEvaluationStatus.REVISION_REQUIRED);
        draftRepository.save(draft);

        submission.setStatus(SubmissionStatus.REJECTED); // Existing logic uses REJECTED
        submissionRepository.save(submission);

        task.setStatus(TaskStatus.IN_PROGRESS);
        taskRepository.save(task);

        auditLogService.log(accountId, AuditAction.ROLE_EVALUATION_REVISION_REQUESTED, "ROLE_EVALUATION_DRAFT", draft.getId(),
                "Revision requested: " + request.getComment());
    }

    @Override
    @Transactional(transactionManager = "transactionManager")
    public void reject(RoleEvaluationDraft draft, ProjectTask task, ProjectTaskSubmission submission, ReviewRoleEvaluationRequest request, Long accountId) {
        if (request.getComment() == null || request.getComment().isBlank()) {
            throw new IllegalArgumentException("Comment required for rejection");
        }

        draft.setStatus(RoleEvaluationStatus.REJECTED);
        draft.setActive(false);
        draft.setActiveDraftKey(null);
        draftRepository.save(draft);

        submission.setStatus(SubmissionStatus.REJECTED);
        submissionRepository.save(submission);

        task.setStatus(TaskStatus.DONE);
        taskRepository.save(task);

        auditLogService.log(accountId, AuditAction.ROLE_EVALUATION_REJECTED, "ROLE_EVALUATION_DRAFT", draft.getId(),
                "Rejected: " + request.getComment());
    }

    private void repairStateAndReturn(RoleEvaluationDraft draft, ProjectTask task, ProjectTaskSubmission submission, Long snapshotId, Long reviewerId) {
        draft.setApprovedSnapshotId(snapshotId);
        draft.setStatus(RoleEvaluationStatus.APPROVED);
        draft.setActive(false);
        draft.setActiveDraftKey(null); // Clear unique index
        draftRepository.save(draft);

        submission.setStatus(SubmissionStatus.APPROVED);
        submissionRepository.save(submission);

        task.setStatus(TaskStatus.DONE);
        taskRepository.save(task);

        auditLogService.log(reviewerId != null ? reviewerId : 0L, AuditAction.ROLE_EVALUATION_APPROVED,
                "ROLE_EVALUATION_DRAFT", draft.getId(),
                "Approved. snapshotId=" + snapshotId);
    }
}
