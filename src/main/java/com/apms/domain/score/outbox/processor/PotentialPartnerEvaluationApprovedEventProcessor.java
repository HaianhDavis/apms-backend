package com.apms.domain.score.outbox.processor;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.SubmissionStatus;
import com.apms.common.enums.TaskStatus;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.ProjectTaskSubmission;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.domain.project.repository.sql.ProjectTaskSubmissionRepository;
import com.apms.domain.score.RoleScoreRuleSet;
import com.apms.domain.score.ScoreSnapshot;
import com.apms.domain.score.draft.RoleEvaluationVersion;
import com.apms.domain.score.dto.RoleEvaluationCalculationRequest;
import com.apms.domain.score.dto.RoleEvaluationCalculationResult;
import com.apms.domain.score.engine.RoleScoringEngine;
import com.apms.domain.score.enums.EvaluationCompletenessStatus;
import com.apms.domain.score.enums.RoleEvaluationOutboxEventType;
import com.apms.domain.score.outbox.RoleEvaluationOutboxEvent;
import com.apms.domain.score.outbox.RoleEvaluationOutboxEventProcessorStrategy;
import com.apms.domain.score.repository.mongo.RoleEvaluationVersionRepository;
import com.apms.domain.score.repository.sql.RoleScoreRuleSetRepository;
import com.apms.domain.score.service.CanonicalScoreSnapshotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class PotentialPartnerEvaluationApprovedEventProcessor implements RoleEvaluationOutboxEventProcessorStrategy {

    private final ProjectTaskRepository taskRepository;
    private final ProjectTaskSubmissionRepository submissionRepository;
    private final AuditLogService auditLogService;
    private final RoleEvaluationVersionRepository versionRepository;
    private final RoleScoringEngine scoringEngine;
    private final RoleScoreRuleSetRepository ruleSetRepository;
    private final CanonicalScoreSnapshotService canonicalScoreSnapshotService;

    @Override
    public boolean supports(RoleEvaluationOutboxEventType eventType) {
        return eventType == RoleEvaluationOutboxEventType.POTENTIAL_PARTNER_EVALUATION_APPROVED;
    }

    @Override
    public void process(RoleEvaluationOutboxEvent event) {
        // 1. Load Immutable Version
        RoleEvaluationVersion version = versionRepository.findById(event.getPayload().getApprovedVersionId())
                .orElseThrow(() -> new IllegalStateException("Version not found: " + event.getPayload().getApprovedVersionId()));

        // 2. Score Calculation from Immutable Version (never mutable draft)
        RoleScoreRuleSet ruleSet = ruleSetRepository.findByEvaluatedRoleAndRuleSetVersion(
                        version.getEvaluatedRole(), "ROLE_SCORING_V1") // fallback/default
                .orElseThrow(() -> new IllegalStateException("Active rule set not found"));

        RoleEvaluationCalculationRequest calcRequest = new RoleEvaluationCalculationRequest();
        calcRequest.setEvaluatedRole(version.getEvaluatedRole());
        calcRequest.setTargetCompanyProfileId(version.getTargetCompanyProfileId());
        calcRequest.setRuleSetVersion(ruleSet.getRuleSetVersion());
        calcRequest.setCalculatedByAccountId(event.getPayload().getActorAccountId());
        calcRequest.setSourceEvaluationDraftId(version.getEvaluationId());
        calcRequest.setApprovalIdempotencyKey(event.getId());
        calcRequest.setApprovedRoleEvaluationVersionId(version.getId());
        calcRequest.setApprovedRoleEvaluationVersionNumber(version.getVersionNumber());
        calcRequest.setEvaluatorRole(version.getEvaluatorRole());
        calcRequest.setAuthoritative(version.getAuthoritative());

        Map<String, BigDecimal> scores = new HashMap<>();
        Map<String, java.util.List<String>> evidenceMap = new HashMap<>();
        version.getCriteria().forEach((k, snap) -> {
            scores.put(k, snap.getRawScore());
            if (snap.getEvidenceReferenceIds() != null && !snap.getEvidenceReferenceIds().isEmpty()) {
                evidenceMap.put(k, new java.util.ArrayList<>(snap.getEvidenceReferenceIds()));
            }
        });
        calcRequest.setCriterionScores(new LinkedHashMap<>(scores));
        calcRequest.setCriterionEvidenceRefs(evidenceMap);

        RoleEvaluationCalculationResult result = scoringEngine.calculate(calcRequest);
        if (result.getCompletenessStatus() != EvaluationCompletenessStatus.COMPLETE) {
            throw new IllegalStateException("Cannot approve: Final score calculation was not COMPLETE.");
        }

        ScoreSnapshot snapshot = canonicalScoreSnapshotService.createCanonicalSnapshot(calcRequest);

        // 3. SQL Entity Transitions
        ProjectTask task = taskRepository.findById(event.getTaskId())
                .orElseThrow(() -> new IllegalStateException("Task not found: " + event.getTaskId()));

        ProjectTaskSubmission submission = submissionRepository.findById(event.getPayload().getSubmissionId())
                .orElseThrow(() -> new IllegalStateException("Submission not found: " + event.getPayload().getSubmissionId()));

        submission.setStatus(SubmissionStatus.APPROVED);
        submissionRepository.save(submission);

        task.setStatus(TaskStatus.DONE);
        task.setCompletedAt(LocalDateTime.now());
        taskRepository.save(task);

        // 4. Canonical POTENTIAL_PARTNER Audit
        auditLogService.log(event.getPayload().getActorAccountId(), AuditAction.ROLE_EVALUATION_APPROVED,
                "ROLE_EVALUATION_DRAFT", version.getEvaluationId(),
                "Approved POTENTIAL_PARTNER evaluation. versionId=" + version.getId() + ", snapshotId=" + snapshot.getScoreSnapshotId() + ", overallScore=" + result.getOverallScore());
    }
}
