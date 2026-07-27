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

@Component
@RequiredArgsConstructor
public class PartnerRoleEvaluationSubmissionStrategy implements RoleEvaluationSubmissionStrategy {

    private final MongoTemplate mongoTemplate;
    private final AccountRepository accountRepository;
    private final PartnerDataSufficiencyEvaluator sufficiencyEvaluator;

    @Override
    public boolean supports(CompanyRole role) {
        return role == CompanyRole.PARTNER;
    }

    @Override
    @Transactional(transactionManager = "mongoTransactionManager")
    public void submit(RoleEvaluationDraft draft, ProjectTask task, ProjectTaskSubmission existingSubmission, SubmitRoleEvaluationRequest request, Long accountId) {

        // 1. Validations
        if (task.getAssignedToAccount() == null || !task.getAssignedToAccount().getId().equals(accountId)) {
            throw new IllegalStateException("Only assigned staff can submit");
        }

        if (draft.getStaleTargetProfile() || draft.getStaleReferenceProfile() || draft.getStaleRuleSet()) {
            throw new IllegalStateException("Cannot submit draft with stale dependencies");
        }

        // Completeness logic
        com.apms.domain.score.dto.draft.RoleEvaluationReadinessResponse readiness = sufficiencyEvaluator.evaluate(draft);
        if (!readiness.isStaffMaySubmit() || readiness.getAggregateCompletenessStatus() == EvaluationCompletenessStatus.INCOMPLETE) {
            throw new IllegalStateException("Data is INCOMPLETE. 6 canonical criteria are required or insufficient data.");
        }

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
                .aggregateCompletenessStatus(readiness.getAggregateCompletenessStatus())
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
    }
}
