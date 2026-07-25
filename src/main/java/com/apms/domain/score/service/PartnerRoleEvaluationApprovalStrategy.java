package com.apms.domain.score.service;

import com.apms.common.enums.OutboxEventStatus;
import com.apms.domain.company.enums.CompanyRole;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.ProjectTaskSubmission;
import com.apms.domain.score.draft.CriterionSnapshot;
import com.apms.domain.score.draft.RoleEvaluationDraft;
import com.apms.domain.score.draft.RoleEvaluationVersion;
import com.apms.domain.score.dto.draft.ReviewRoleEvaluationRequest;
import com.apms.domain.score.enums.RoleEvaluationOutboxEventType;
import com.apms.domain.score.enums.RoleEvaluationStatus;
import com.apms.domain.score.enums.EvaluationCompletenessStatus;
import com.apms.domain.score.outbox.RoleEvaluationOutboxEvent;
import com.apms.domain.score.outbox.RoleEvaluationOutboxPayload;
import com.apms.domain.score.outbox.RoleEvaluationOutboxPayloadHasher;
import com.apms.domain.score.repository.mongo.RoleEvaluationDraftRepository;
import com.apms.common.security.ProjectSecurityEvaluator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class PartnerRoleEvaluationApprovalStrategy implements RoleEvaluationApprovalStrategy {

    private final MongoTemplate mongoTemplate;
    private final PartnerDataSufficiencyEvaluator sufficiencyEvaluator;
    private final ProjectSecurityEvaluator projectSecurityEvaluator;

    @Override
    public boolean supports(CompanyRole role) {
        return role == CompanyRole.PARTNER;
    }

    @Override
    @Transactional(transactionManager = "mongoTransactionManager")
    public void approve(RoleEvaluationDraft draft, ProjectTask task, ProjectTaskSubmission submission, ReviewRoleEvaluationRequest request, Long accountId, String idempotencyKey) {

        if (!projectSecurityEvaluator.isManager(task.getProject().getId())) {
            throw new SecurityException("User is not authorized as Manager for this project");
        }

        // 1. Validation
        if (draft.getStatus() != RoleEvaluationStatus.IN_REVIEW) {
            throw new IllegalStateException("Draft must be IN_REVIEW to approve");
        }

        // Authorization would be checked in the caller (e.g. Manager check)

        if (draft.getStaleTargetProfile() || draft.getStaleReferenceProfile() || draft.getStaleRuleSet()) {
            throw new IllegalStateException("Draft contains stale versions. Cannot approve.");
        }

        com.apms.domain.score.dto.draft.RoleEvaluationReadinessResponse readiness = sufficiencyEvaluator.evaluate(draft);
        if (readiness.getAggregateCompletenessStatus() == EvaluationCompletenessStatus.INCOMPLETE) {
            throw new IllegalStateException("Cannot approve INCOMPLETE evaluation.");
        }
        if (readiness.getAggregateCompletenessStatus() == EvaluationCompletenessStatus.PARTIAL) {
            if (request.getComment() == null || request.getComment().isBlank()) {
                throw new IllegalArgumentException("Manager justification is required for PARTIAL evaluations.");
            }
        }

        Integer nextVersion = (draft.getCurrentApprovedVersionNumber() == null ? 0 : draft.getCurrentApprovedVersionNumber()) + 1;
        String versionId = UUID.randomUUID().toString();

        Map<String, CriterionSnapshot> criteriaMap = new HashMap<>();
        draft.getCriterionInputs().forEach((k, v) -> {
            com.apms.domain.score.draft.AutomaticSuggestion suggestion = draft.getAutomaticSuggestions() != null ? draft.getAutomaticSuggestions().get(k) : null;
            com.apms.domain.score.dto.draft.CriterionReadinessResult crr = readiness.getCriterionResults() != null ? readiness.getCriterionResults().get(k) : null;

            String suffStatus = crr != null && crr.getSufficiencyStatus() != null ? crr.getSufficiencyStatus().name() : null;
            String missingDataEx = crr != null && crr.getMissingCategories() != null && !crr.getMissingCategories().isEmpty() ? String.join(", ", crr.getMissingCategories()) : null;
            Map<String, Object> aiMetadata = new HashMap<>();
            java.math.BigDecimal aiConf = null;
            com.apms.domain.score.enums.CriterionSuggestionReviewStatus revStatus = null;

            if (suggestion != null) {
                aiConf = suggestion.getConfidence();
                revStatus = suggestion.getEffectiveReviewStatus();
                if (suggestion.getModelProvider() != null) aiMetadata.put("modelProvider", suggestion.getModelProvider());
                if (suggestion.getModelVersion() != null) aiMetadata.put("modelVersion", suggestion.getModelVersion());
                if (suggestion.getPromptVersion() != null) aiMetadata.put("promptVersion", suggestion.getPromptVersion());
                if (suggestion.getGenerationId() != null) aiMetadata.put("generationId", suggestion.getGenerationId());
            }

            CriterionSnapshot snap = CriterionSnapshot.builder()
                    .criterionKey(k)
                    .inputMethod(v.getInputMethod())
                    .finalRationale(v.getExplanation())
                    .factualFindings(null)
                    .evidenceReferenceIds(v.getEvidenceIds() != null ? new java.util.ArrayList<>(v.getEvidenceIds()) : new java.util.ArrayList<>())
                    .dataSufficiencyStatus(suffStatus)
                    .missingDataExplanation(missingDataEx)
                    .suggestionReviewStatus(revStatus)
                    .staffEdited(v.getInputMethod() == com.apms.domain.score.enums.CriterionInputMethod.AI_ASSISTED_EDITED)
                    .managerFeedback(v.getOverrideReason())
                    .acceptedAiMetadata(aiMetadata)
                    .aiConfidence(aiConf)
                    .build();
            criteriaMap.put(k, snap);
        });

        RoleEvaluationVersion version = RoleEvaluationVersion.builder()
                .id(versionId)
                .evaluationId(draft.getId())
                .projectId(task.getProject().getId())
                .taskId(task.getId())
                .targetCompanyProfileId(draft.getTargetProfileDocumentId())
                .evaluatedRole(CompanyRole.PARTNER)
                .versionNumber(nextVersion)
                .approvedDraftRevision(draft.getWorkingRevisionNumber())
                .status(RoleEvaluationStatus.APPROVED)
                .evaluationPeriod(draft.getEvaluationPeriod())
                .criteria(criteriaMap)
                .sourceReferences(draft.getPinnedSourceReferences())
                .sourceSnapshotHash(draft.getSourceSnapshotHash())
                .submittedByAccountId(draft.getSubmittedByAccountId())
                .submittedAt(draft.getSubmittedAt())
                .approvedByAccountId(accountId)
                .approvedAt(LocalDateTime.now())
                .reviewComment(request.getComment())
                .completenessStatus(readiness.getAggregateCompletenessStatus())
                .partialApprovalJustification(readiness.getAggregateCompletenessStatus() == EvaluationCompletenessStatus.PARTIAL ? request.getComment() : null)
                .schemaVersion(1)
                .createdAt(LocalDateTime.now())
                .build();

        mongoTemplate.insert(version);

        String eventId = draft.getId() + "_" + nextVersion + "_APPROVED";

        RoleEvaluationOutboxPayload outboxPayload = RoleEvaluationOutboxPayload.builder()
                        .eventId(eventId)
                        .eventType(RoleEvaluationOutboxEventType.PARTNER_EVALUATION_APPROVED.name())
                        .evaluationId(draft.getId())
                        .projectId(task.getProject().getId())
                        .taskId(task.getId())
                        .submissionId(submission.getId())
                        .targetCompanyProfileId(draft.getTargetProfileDocumentId())
                        .actorAccountId(accountId)
                        .submittedRevisionNumber(draft.getWorkingRevisionNumber())
                        .approvedVersionId(versionId)
                        .approvedVersionNumber(nextVersion)
                        .managerFeedback(request.getComment())
                        .aggregateCompletenessStatus(readiness.getAggregateCompletenessStatus())
                        .managerJustification(readiness.getAggregateCompletenessStatus() == EvaluationCompletenessStatus.PARTIAL ? request.getComment() : null)
                        .occurredAt(LocalDateTime.now())
                        .payloadVersion(1)
                        .build();

        RoleEvaluationOutboxEvent outboxEvent = RoleEvaluationOutboxEvent.builder()
                .eventId(eventId)
                .evaluationId(draft.getId())
                .projectId(task.getProject().getId())
                .taskId(task.getId())
                .eventType(RoleEvaluationOutboxEventType.PARTNER_EVALUATION_APPROVED)
                .status(OutboxEventStatus.PENDING)
                .payload(outboxPayload)
                .payloadHash(RoleEvaluationOutboxPayloadHasher.hash(outboxPayload))
                .createdAt(LocalDateTime.now())
                .build();
        mongoTemplate.insert(outboxEvent);

        Query query = new Query(Criteria.where("id").is(draft.getId())
                .and("status").is(RoleEvaluationStatus.IN_REVIEW)
                .and("workingRevisionNumber").is(draft.getSubmittedRevisionNumber())
                .and("sourceSnapshotHash").is(draft.getSubmittedSourceSnapshotHash())
                .and("optimisticVersion").is(draft.getOptimisticVersion()));

        Update update = new Update()
                .set("status", RoleEvaluationStatus.APPROVED)
                .set("active", false)
                .unset("activeDraftKey")
                .set("currentApprovedVersionId", versionId)
                .set("currentApprovedVersionNumber", nextVersion)
                .set("reviewedByAccountId", accountId)
                .set("reviewedAt", LocalDateTime.now())
                .set("reviewComment", request.getComment())
                .inc("optimisticVersion", 1);

        long modified = mongoTemplate.updateFirst(query, update, RoleEvaluationDraft.class).getModifiedCount();
        if (modified == 0) {
            throw new IllegalStateException("Draft state changed or optimistic lock failed");
        }
    }

    @Override
    @Transactional(transactionManager = "mongoTransactionManager")
    public void requestRevision(RoleEvaluationDraft draft, ProjectTask task, ProjectTaskSubmission submission, ReviewRoleEvaluationRequest request, Long accountId) {
        if (request.getComment() == null || request.getComment().isBlank()) {
            throw new IllegalArgumentException("Comment required for requesting revision");
        }

        Integer nextRevision = draft.getWorkingRevisionNumber() + 1;
        String eventId = draft.getId() + "_" + draft.getWorkingRevisionNumber() + "_" + nextRevision + "_REVISION_REQUESTED";

        RoleEvaluationOutboxPayload outboxPayload = RoleEvaluationOutboxPayload.builder()
                        .eventId(eventId)
                        .eventType(RoleEvaluationOutboxEventType.PARTNER_EVALUATION_REVISION_REQUESTED.name())
                        .evaluationId(draft.getId())
                        .projectId(task.getProject().getId())
                        .taskId(task.getId())
                        .submissionId(submission.getId())
                        .targetCompanyProfileId(draft.getTargetProfileDocumentId())
                        .actorAccountId(accountId)
                        .submittedRevisionNumber(draft.getWorkingRevisionNumber())
                        .managerFeedback(request.getComment())
                        .occurredAt(LocalDateTime.now())
                        .payloadVersion(1)
                        .build();

        RoleEvaluationOutboxEvent outboxEvent = RoleEvaluationOutboxEvent.builder()
                .eventId(eventId)
                .evaluationId(draft.getId())
                .projectId(task.getProject().getId())
                .taskId(task.getId())
                .eventType(RoleEvaluationOutboxEventType.PARTNER_EVALUATION_REVISION_REQUESTED)
                .status(OutboxEventStatus.PENDING)
                .payload(outboxPayload)
                .payloadHash(RoleEvaluationOutboxPayloadHasher.hash(outboxPayload))
                .createdAt(LocalDateTime.now())
                .build();
        mongoTemplate.insert(outboxEvent);

        Query query = new Query(Criteria.where("id").is(draft.getId())
                .and("status").is(RoleEvaluationStatus.IN_REVIEW)
                .and("workingRevisionNumber").is(draft.getSubmittedRevisionNumber())
                .and("sourceSnapshotHash").is(draft.getSubmittedSourceSnapshotHash())
                .and("optimisticVersion").is(draft.getOptimisticVersion()));

        Update update = new Update()
                .set("status", RoleEvaluationStatus.REVISION_REQUIRED)
                .set("workingRevisionNumber", nextRevision)
                .set("reviewedByAccountId", accountId)
                .set("reviewedAt", LocalDateTime.now())
                .set("reviewComment", request.getComment())
                .inc("optimisticVersion", 1);

        long modified = mongoTemplate.updateFirst(query, update, RoleEvaluationDraft.class).getModifiedCount();
        if (modified == 0) {
            throw new IllegalStateException("Draft state changed or optimistic lock failed");
        }
    }

    @Override
    @Transactional(transactionManager = "mongoTransactionManager")
    public void reject(RoleEvaluationDraft draft, ProjectTask task, ProjectTaskSubmission submission, ReviewRoleEvaluationRequest request, Long accountId) {
        throw new UnsupportedOperationException("REJECT decision is not supported for PARTNER evaluations");
    }
}
