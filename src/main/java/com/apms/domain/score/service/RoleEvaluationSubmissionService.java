package com.apms.domain.score.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.SubmissionStatus;
import com.apms.common.enums.SubmissionType;
import com.apms.common.enums.TaskStatus;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.ProjectTaskSubmission;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.domain.project.repository.sql.ProjectTaskSubmissionRepository;
import com.apms.domain.score.draft.CriterionInput;
import com.apms.domain.score.draft.RoleEvaluationDraft;
import com.apms.domain.score.dto.draft.SubmitRoleEvaluationRequest;
import com.apms.domain.score.enums.CriterionInputMethod;
import com.apms.domain.score.enums.RoleEvaluationStatus;
import com.apms.domain.score.repository.mongo.RoleEvaluationDraftRepository;
import com.apms.domain.user.Account;
import com.apms.domain.user.repository.sql.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class RoleEvaluationSubmissionService {

    private final RoleEvaluationDraftRepository draftRepository;
    private final ProjectTaskRepository taskRepository;
    private final ProjectTaskSubmissionRepository submissionRepository;
    private final AccountRepository accountRepository;
    private final AuditLogService auditLogService;

    @Transactional
    public void submitDraft(String draftId, SubmitRoleEvaluationRequest request, Long accountId) {
        RoleEvaluationDraft draft = draftRepository.findById(draftId)
                .orElseThrow(() -> new IllegalArgumentException("Draft not found: " + draftId));

        if (draft.getStatus() != RoleEvaluationStatus.DRAFT && draft.getStatus() != RoleEvaluationStatus.REVISION_REQUIRED) {
            throw new IllegalStateException("Draft cannot be submitted in current state: " + draft.getStatus());
        }

        // Validate completeness
        for (Map.Entry<String, CriterionInput> entry : draft.getCriterionInputs().entrySet()) {
            CriterionInput input = entry.getValue();
            if (input.getRawScore() == null) {
                throw new IllegalStateException("Criterion " + entry.getKey() + " has no score");
            }
            if (input.getExplanation() == null || input.getExplanation().isBlank()) {
                throw new IllegalStateException("Criterion " + entry.getKey() + " must have an explanation");
            }
            if (input.getInputMethod() != CriterionInputMethod.AUTOMATIC_PROPOSAL) {
                if (input.getEvidenceIds() == null || input.getEvidenceIds().isEmpty()) {
                    throw new IllegalStateException("Manual criterion " + entry.getKey() + " must have evidence");
                }
            }
        }
        
        // Ensure one active submission
        boolean hasActive = submissionRepository.findByProjectTask_Id(draft.getTaskId()).stream()
                .anyMatch(s -> s.getSubmissionType() == SubmissionType.ROLE_EVALUATION && 
                               (s.getStatus() == SubmissionStatus.IN_REVIEW || s.getStatus() == SubmissionStatus.SUBMITTED));
        if (hasActive) {
            throw new IllegalStateException("An active submission already exists for this task.");
        }

        ProjectTask task = taskRepository.findById(draft.getTaskId())
                .orElseThrow(() -> new IllegalStateException("Task not found"));

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalStateException("Account not found"));

        ProjectTaskSubmission submission = ProjectTaskSubmission.builder()
                .project(task.getProject())
                .projectTask(task)
                .submissionType(SubmissionType.ROLE_EVALUATION)
                .targetEntityType("ROLE_EVALUATION_DRAFT")
                .targetEntityId(draft.getId())
                .status(SubmissionStatus.IN_REVIEW)
                .submittedByAccount(account)
                .submittedAt(LocalDateTime.now())
                .build();
        submissionRepository.save(submission);

        task.setStatus(TaskStatus.IN_REVIEW);
        taskRepository.save(task);

        draft.setStatus(RoleEvaluationStatus.IN_REVIEW);
        draft.setSubmittedByAccountId(accountId);
        draft.setSubmittedAt(LocalDateTime.now());
        draftRepository.save(draft);
        
        auditLogService.log(accountId, AuditAction.ROLE_EVALUATION_SUBMITTED, "ROLE_EVALUATION_DRAFT", draftId,
                "Submitted evaluation draft for task=" + draft.getTaskId());
    }
}
