package com.apms.domain.score.service;

import com.apms.common.enums.OutboxEventStatus;
import com.apms.domain.company.enums.CompanyRole;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.ProjectTaskSubmission;
import com.apms.domain.score.draft.RoleEvaluationDraft;
import com.apms.domain.score.dto.draft.SubmitRoleEvaluationRequest;
import com.apms.domain.score.enums.RoleEvaluationOutboxEventType;
import com.apms.domain.score.enums.RoleEvaluationStatus;
import com.apms.domain.score.outbox.RoleEvaluationOutboxEvent;
import com.apms.domain.score.enums.EvaluationCompletenessStatus;
import com.apms.domain.score.outbox.RoleEvaluationOutboxPayload;
import com.apms.domain.score.outbox.RoleEvaluationOutboxPayloadHasher;
import com.apms.domain.user.repository.sql.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Synchronous submission strategy for POTENTIAL_PARTNER role evaluations.
 */
@Component
@RequiredArgsConstructor
public class PotentialPartnerRoleEvaluationSubmissionStrategy implements RoleEvaluationSubmissionStrategy {

    private final MongoTemplate mongoTemplate;
    private final AccountRepository accountRepository; // retained for validations if needed
    private final PotentialPartnerDataSufficiencyEvaluator sufficiencyEvaluator;

    @Override
    public boolean supports(CompanyRole role) {
        return role == CompanyRole.POTENTIAL_PARTNER;
    }

    @Override
    @Transactional(transactionManager = "transactionManager") // Synchronous SQL transaction
    public void submit(RoleEvaluationDraft draft, ProjectTask task, ProjectTaskSubmission existingSubmission, SubmitRoleEvaluationRequest request, Long accountId) {

        // 1. Validations
        if (task.getAssignedToAccount() == null || !task.getAssignedToAccount().getId().equals(accountId)) {
            throw new IllegalStateException("Only assigned staff can submit");
        }

        if (draft.getStaleTargetProfile() || draft.getStaleReferenceProfile() || draft.getStaleRuleSet()) {
            throw new IllegalStateException("Cannot submit draft with stale dependencies");
        }

        // Completeness logic — financial-data policy enforced
        com.apms.domain.score.dto.draft.RoleEvaluationReadinessResponse readiness = sufficiencyEvaluator.evaluate(draft);
        if (!readiness.isStaffMaySubmit() || readiness.getAggregateCompletenessStatus() == EvaluationCompletenessStatus.INCOMPLETE) {
            throw new IllegalStateException("Data is INCOMPLETE. 6 canonical POTENTIAL_PARTNER criteria are required with sufficient evidence.");
        }

        // 2. Draft CAS (Mongo)
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

        // Update local object
        draft.setStatus(RoleEvaluationStatus.IN_REVIEW);

        // 3. Create Outbox Event
        String eventId = java.util.UUID.randomUUID().toString();

        RoleEvaluationOutboxPayload payload = RoleEvaluationOutboxPayload.builder()
                .eventId(eventId)
                .eventType(RoleEvaluationOutboxEventType.POTENTIAL_PARTNER_EVALUATION_SUBMITTED.name())
                .evaluationId(draft.getId())
                .projectId(task.getProject().getId())
                .taskId(task.getId())
                .targetCompanyProfileId(draft.getTargetProfileDocumentId())
                .actorAccountId(accountId)
                .submittedRevisionNumber(draft.getWorkingRevisionNumber())
                .submittedSourceSnapshotHash(draft.getSourceSnapshotHash())
                .occurredAt(LocalDateTime.now())
                .payloadVersion(1)
                .build();

        RoleEvaluationOutboxEvent event = RoleEvaluationOutboxEvent.builder()
                .id(eventId)
                .eventId(eventId)
                .evaluationId(draft.getId())
                .projectId(task.getProject().getId())
                .taskId(task.getId())
                .eventType(RoleEvaluationOutboxEventType.POTENTIAL_PARTNER_EVALUATION_SUBMITTED)
                .status(OutboxEventStatus.PENDING)
                .payload(payload)
                .payloadHash(RoleEvaluationOutboxPayloadHasher.hash(payload))
                .createdAt(LocalDateTime.now())
                .nextAttemptAt(LocalDateTime.now())
                .attemptCount(0)
                .build();

        mongoTemplate.insert(event);
    }
}
