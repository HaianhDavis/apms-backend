package com.apms.domain.score.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.SubmissionStatus;
import com.apms.common.enums.SubmissionType;
import com.apms.common.enums.SystemRole;
import com.apms.common.enums.TaskStatus;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.company.enums.CompanyRole;
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
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class CompetitorRoleEvaluationSubmissionStrategy implements RoleEvaluationSubmissionStrategy {

    private final RoleEvaluationDraftRepository draftRepository;
    private final ProjectTaskRepository taskRepository;
    private final ProjectTaskSubmissionRepository submissionRepository;
    private final AccountRepository accountRepository;
    private final AuditLogService auditLogService;

    @Override
    public boolean supports(CompanyRole role) {
        return role == CompanyRole.COMPETITOR;
    }

    @Override
    public void submit(RoleEvaluationDraft draft, ProjectTask task, ProjectTaskSubmission existingSubmission, SubmitRoleEvaluationRequest request, Long accountId) {
        SystemRole evaluatorRole = RoleEvaluationAuthorityResolver.currentEvaluatorRoleOrDefault(SystemRole.BUSINESS_DEVELOPMENT_STAFF);
        boolean managerOrOwner = evaluatorRole == SystemRole.BUSINESS_DEVELOPMENT_MANAGER || evaluatorRole == SystemRole.BUSINESS_OWNER;
        if (!managerOrOwner
                && (task.getAssignedToAccount() == null || !task.getAssignedToAccount().getId().equals(accountId))) {
            throw new IllegalStateException("Only assigned staff, Manager, or Owner can submit");
        }

        if (draft.getStatus() != RoleEvaluationStatus.DRAFT && draft.getStatus() != RoleEvaluationStatus.REVISION_REQUIRED) {
            throw new IllegalStateException("Draft cannot be submitted in current state: " + draft.getStatus());
        }

        // Validate completeness
        for (Map.Entry<String, CriterionInput> entry : draft.getCriterionInputs().entrySet()) {
            CriterionInput input = entry.getValue();
            if (input.getRawScore() == null) {
                throw new IllegalStateException("Criterion " + entry.getKey() + " has no score");
            }
            if (input.getRawScore().compareTo(BigDecimal.ZERO) < 0 || input.getRawScore().compareTo(new BigDecimal("100")) > 0) {
                throw new IllegalStateException("Criterion " + entry.getKey() + " score must be 0-100");
            }
            if (input.getExplanation() == null || input.getExplanation().isBlank()) {
                throw new IllegalStateException("Criterion " + entry.getKey() + " must have an explanation");
            }
            if (input.getInputMethod() != CriterionInputMethod.AUTOMATIC_PROPOSAL) {
                if (input.getEvidenceIds() == null || input.getEvidenceIds().isEmpty()) {
                    throw new IllegalStateException("Manual criterion " + entry.getKey() + " must have evidence");
                }
            }
            if (input.getManagerConfirmed() != null && input.getManagerConfirmed()) {
                throw new IllegalStateException("Criterion " + entry.getKey() + " cannot be manager-confirmed before approval");
            }
        }

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

        auditLogService.log(accountId, AuditAction.ROLE_EVALUATION_SUBMITTED, "ROLE_EVALUATION_DRAFT", draft.getId(),
                "Submitted evaluation draft for task=" + draft.getTaskId());
    }
}
