package com.apms.domain.score.service;

import com.apms.common.enums.SubmissionStatus;
import com.apms.common.enums.SubmissionType;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.ProjectTaskSubmission;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.domain.project.repository.sql.ProjectTaskSubmissionRepository;
import com.apms.domain.score.draft.RoleEvaluationDraft;
import com.apms.domain.score.dto.draft.ReviewRoleEvaluationRequest;
import com.apms.domain.score.enums.RoleEvaluationReviewDecision;
import com.apms.domain.score.enums.RoleEvaluationStatus;
import com.apms.domain.score.repository.mongo.RoleEvaluationDraftRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RoleEvaluationApprovalService {

    private final RoleEvaluationDraftRepository draftRepository;
    private final ProjectTaskRepository taskRepository;
    private final ProjectTaskSubmissionRepository submissionRepository;
    private final List<RoleEvaluationApprovalStrategy> strategies;
    private final RoleEvaluationAuthorityService authorityService;

    public void reviewDraft(String draftId, ReviewRoleEvaluationRequest request, Long accountId, String idempotencyKeyHeader) {
        RoleEvaluationDraft draft = draftRepository.findById(draftId)
                .orElseThrow(() -> new IllegalArgumentException("Draft not found: " + draftId));
        authorityService.assertManagerMayReview(draft);

        if (draft.getStatus() != RoleEvaluationStatus.IN_REVIEW && draft.getStatus() != RoleEvaluationStatus.APPROVAL_FAILED) {
            throw new IllegalStateException("Draft cannot be reviewed in current state: " + draft.getStatus());
        }

        ProjectTask task = taskRepository.findById(draft.getTaskId())
                .orElseThrow(() -> new IllegalStateException("Task not found"));

        if (!task.getProject().getId().equals(draft.getProjectId())) {
            throw new IllegalStateException("Draft project ID mismatch");
        }

        ProjectTaskSubmission submission = submissionRepository.findByProjectTask_Id(draft.getTaskId())
                .stream()
                .filter(s -> s.getSubmissionType() == SubmissionType.ROLE_EVALUATION &&
                             (s.getStatus() == SubmissionStatus.IN_REVIEW || s.getStatus() == SubmissionStatus.SUBMITTED))
                .findFirst().orElseThrow(() -> new IllegalStateException("Active submission not found"));

        draft.setReviewedByAccountId(accountId);
        draft.setReviewComment(request.getComment());

        RoleEvaluationApprovalStrategy strategy = strategies.stream()
                .filter(s -> s.supports(draft.getEvaluatedRole()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No approval strategy for role: " + draft.getEvaluatedRole()));

        if (request.getDecision() == RoleEvaluationReviewDecision.REJECT) {
            strategy.reject(draft, task, submission, request, accountId);
        } else if (request.getDecision() == RoleEvaluationReviewDecision.REQUEST_REVISION) {
            strategy.requestRevision(draft, task, submission, request, accountId);
        } else if (request.getDecision() == RoleEvaluationReviewDecision.APPROVE) {
            String idempotencyKey = idempotencyKeyHeader != null ? idempotencyKeyHeader : UUID.randomUUID().toString();
            strategy.approve(draft, task, submission, request, accountId, idempotencyKey);
        }
    }
}
