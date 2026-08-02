package com.apms.domain.score.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.OutboxEventStatus;
import com.apms.common.enums.SubmissionStatus;
import com.apms.common.enums.SubmissionType;
import com.apms.common.enums.TaskStatus;
import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.company.enums.CompanyRole;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.ProjectTaskSubmission;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.domain.project.repository.sql.ProjectTaskSubmissionRepository;
import com.apms.domain.score.draft.CriterionInput;
import com.apms.domain.score.draft.RoleEvaluationDraft;
import com.apms.domain.score.dto.draft.SubmitRoleEvaluationRequest;
import com.apms.domain.score.enums.RoleEvaluationOutboxEventType;
import com.apms.domain.score.enums.RoleEvaluationStatus;
import com.apms.domain.score.outbox.RoleEvaluationOutboxEvent;
import com.apms.domain.score.enums.EvaluationCompletenessStatus;
import com.apms.domain.score.outbox.RoleEvaluationOutboxPayload;
import com.apms.domain.score.outbox.RoleEvaluationOutboxPayloadHasher;
import com.apms.domain.score.registry.CanonicalRoleCriteria;
import com.apms.domain.user.Account;
import com.apms.domain.user.repository.sql.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
public class PartnerRoleEvaluationSubmissionStrategy implements RoleEvaluationSubmissionStrategy {

    private final MongoTemplate mongoTemplate;
    private final ProjectTaskRepository taskRepository;
    private final ProjectTaskSubmissionRepository submissionRepository;
    private final AccountRepository accountRepository;
    private final PartnerDataSufficiencyEvaluator sufficiencyEvaluator;
    private final AuditLogService auditLogService;

    @Override
    public boolean supports(CompanyRole role) {
        return role == CompanyRole.PARTNER;
    }

    @Override
    public void submit(RoleEvaluationDraft draft, ProjectTask task, ProjectTaskSubmission existingSubmission, SubmitRoleEvaluationRequest request, Long accountId) {

        // 1. Validations
        if (task.getAssignedToAccount() == null || !task.getAssignedToAccount().getId().equals(accountId)) {
            throw new IllegalStateException("Only assigned staff can submit");
        }

        if (draft.getStaleTargetProfile() || draft.getStaleReferenceProfile() || draft.getStaleRuleSet()) {
            throw new IllegalStateException("Cannot submit draft with stale dependencies");
        }

        validateStaffConfirmedCriteria(draft);
        EvaluationCompletenessStatus completenessStatus = EvaluationCompletenessStatus.COMPLETE;

        String eventId = draft.getId() + "_" + draft.getWorkingRevisionNumber() + "_SUBMITTED";

        // 2. Outbox Event
        RoleEvaluationOutboxPayload outboxPayload = RoleEvaluationOutboxPayload.builder()
                .eventId(eventId)
                .eventType(RoleEvaluationOutboxEventType.PARTNER_EVALUATION_SUBMITTED.name())
                .evaluationId(draft.getId())
                .projectId(task.getProject().getId())
                .taskId(task.getId())
                .targetCompanyProfileId(draft.getTargetProfileDocumentId())
                .actorAccountId(accountId)
                .submittedRevisionNumber(draft.getWorkingRevisionNumber())
                .submittedSourceSnapshotHash(draft.getSourceSnapshotHash())
                .aggregateCompletenessStatus(completenessStatus)
                .occurredAt(LocalDateTime.now())
                .payloadVersion(1)
                .build();

        RoleEvaluationOutboxEvent outboxEvent = RoleEvaluationOutboxEvent.builder()
                .eventId(eventId)
                .evaluationId(draft.getId())
                .projectId(task.getProject().getId())
                .taskId(task.getId())
                .eventType(RoleEvaluationOutboxEventType.PARTNER_EVALUATION_SUBMITTED)
                .status(OutboxEventStatus.PENDING)
                .payload(outboxPayload)
                .payloadHash(RoleEvaluationOutboxPayloadHasher.hash(outboxPayload))
                .createdAt(LocalDateTime.now())
                .build();

        mongoTemplate.insert(outboxEvent);

        // 3. Draft CAS
        Query query = new Query(Criteria.where("id").is(draft.getId())
                .and("status").in(RoleEvaluationStatus.DRAFT, RoleEvaluationStatus.REVISION_REQUIRED)
                .and("optimisticVersion").is(draft.getOptimisticVersion()));

        Update update = new Update()
                .set("status", RoleEvaluationStatus.IN_REVIEW)
                .set("submittedRevisionNumber", draft.getWorkingRevisionNumber())
                .set("submittedSourceSnapshotHash", draft.getSourceSnapshotHash())
                .set("submittedByAccountId", accountId)
                .set("submittedAt", LocalDateTime.now())
                .inc("optimisticVersion", 1);

        long modified = mongoTemplate.updateFirst(query, update, RoleEvaluationDraft.class).getModifiedCount();
        if (modified == 0) {
            throw new IllegalStateException("Draft state changed or optimistic lock failed");
        }

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalStateException("Account not found: " + accountId));

        ProjectTaskSubmission submission = existingSubmission != null
                ? existingSubmission
                : ProjectTaskSubmission.builder()
                .project(task.getProject())
                .projectTask(task)
                .submissionType(SubmissionType.ROLE_EVALUATION)
                .targetEntityType("ROLE_EVALUATION_DRAFT")
                .targetEntityId(draft.getId())
                .submittedByAccount(account)
                .build();
        submission.setStatus(SubmissionStatus.IN_REVIEW);
        submission.setSubmittedByAccount(account);
        submission.setSubmittedAt(LocalDateTime.now());
        submissionRepository.save(submission);

        task.setStatus(TaskStatus.IN_REVIEW);
        task.setCompletedAt(null);
        taskRepository.save(task);

        auditLogService.log(accountId, AuditAction.PARTNER_EVALUATION_SUBMITTED, "ROLE_EVALUATION_DRAFT", draft.getId(),
                "Submitted partner evaluation for review");
    }

    private void validateStaffConfirmedCriteria(RoleEvaluationDraft draft) {
        List<String> missing = CanonicalRoleCriteria.PARTNER_CRITERIA.stream()
                .filter(criterionKey -> !isCriterionComplete(draft, criterionKey))
                .toList();

        if (!missing.isEmpty()) {
            throw new BusinessValidationException("Data is INCOMPLETE. Staff score, reason, and evidence are required for all 6 PARTNER criteria. Missing: " + String.join(", ", missing));
        }
    }

    private boolean isCriterionComplete(RoleEvaluationDraft draft, String criterionKey) {
        CriterionInput input = draft.getCriterionInputs() != null ? draft.getCriterionInputs().get(criterionKey) : null;
        boolean hasScore = input != null
                && input.getRawScore() != null
                && input.getRawScore().compareTo(BigDecimal.ZERO) >= 0
                && input.getRawScore().compareTo(new BigDecimal("100")) <= 0;
        boolean hasReason = input != null && StringUtils.hasText(input.getExplanation());
        boolean hasEvidence = draft.getCriterionEvidence() != null
                && draft.getCriterionEvidence().getOrDefault(criterionKey, List.of()).stream()
                .anyMatch(evidence -> StringUtils.hasText(evidence.getEvidenceId()) || StringUtils.hasText(evidence.getRawDocumentId()));
        return hasScore && hasReason && hasEvidence;
    }
}
