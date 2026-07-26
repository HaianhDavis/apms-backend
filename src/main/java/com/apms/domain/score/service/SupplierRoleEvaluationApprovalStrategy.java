package com.apms.domain.score.service;

import com.apms.common.enums.OutboxEventStatus;
import com.apms.domain.company.enums.CompanyRole;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.ProjectTaskSubmission;
import com.apms.domain.score.draft.CriterionSnapshot;
import com.apms.domain.score.draft.RoleEvaluationDraft;
import com.apms.domain.score.draft.RoleEvaluationVersion;
import com.apms.domain.score.dto.draft.CriterionReadinessResult;
import com.apms.domain.score.dto.draft.ReviewRoleEvaluationRequest;
import com.apms.domain.score.dto.draft.RoleEvaluationReadinessResponse;
import com.apms.domain.score.enums.RoleEvaluationStatus;
import com.apms.domain.score.enums.RoleEvaluationOutboxEventType;
import com.apms.domain.score.outbox.RoleEvaluationOutboxEvent;
import com.apms.domain.score.outbox.RoleEvaluationOutboxPayload;
import com.apms.domain.score.outbox.RoleEvaluationOutboxPayloadHasher;
import com.apms.domain.score.enums.EvaluationCompletenessStatus;
import com.apms.domain.score.enums.CriterionSuggestionReviewStatus;
import com.apms.domain.score.enums.CriterionInputMethod;
import com.apms.common.security.ProjectSecurityEvaluator;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Synchronous approval strategy for SUPPLIER role evaluations.
 * <p>
 * Creates an immutable {@link RoleEvaluationVersion} AND a {@link RoleEvaluationOutboxEvent}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SupplierRoleEvaluationApprovalStrategy implements RoleEvaluationApprovalStrategy {

    private final MongoTemplate mongoTemplate;
    private final SupplierDataSufficiencyEvaluator sufficiencyEvaluator;
    private final ProjectSecurityEvaluator projectSecurityEvaluator;

    @Override
    public boolean supports(CompanyRole role) {
        return role == CompanyRole.SUPPLIER;
    }

    @Override
    @Transactional(transactionManager = "transactionManager")
    public void approve(RoleEvaluationDraft draft, ProjectTask task, ProjectTaskSubmission submission, ReviewRoleEvaluationRequest request, Long accountId, String idempotencyKey) {

        if (!projectSecurityEvaluator.isManager(task.getProject().getId())) {
            throw new SecurityException("User is not authorized as Manager for this project");
        }

        // 1. Validation
        if (draft.getStatus() != RoleEvaluationStatus.IN_REVIEW) {
            throw new IllegalStateException("Draft must be IN_REVIEW to approve");
        }

        if (draft.getStaleTargetProfile() || draft.getStaleReferenceProfile() || draft.getStaleRuleSet()) {
            throw new IllegalStateException("Draft contains stale versions. Cannot approve.");
        }

        RoleEvaluationReadinessResponse readiness = sufficiencyEvaluator.evaluate(draft);
        if (readiness.getAggregateCompletenessStatus() == EvaluationCompletenessStatus.INCOMPLETE) {
            throw new IllegalStateException("Cannot approve INCOMPLETE evaluation.");
        }
        if (readiness.getAggregateCompletenessStatus() == EvaluationCompletenessStatus.PARTIAL) {
            if (request.getComment() == null || request.getComment().isBlank()) {
                throw new IllegalArgumentException("Manager justification is required for PARTIAL evaluations.");
            }
        }

        // 2. RoleEvaluationVersion Creation (Mongo)
        Integer nextVersion = (draft.getCurrentApprovedVersionNumber() == null ? 0 : draft.getCurrentApprovedVersionNumber()) + 1;
        String versionId = UUID.randomUUID().toString();

        Map<String, CriterionSnapshot> criteriaMap = new HashMap<>();
        draft.getCriterionInputs().forEach((k, v) -> {
            com.apms.domain.score.draft.AutomaticSuggestion suggestion = draft.getAutomaticSuggestions() != null ? draft.getAutomaticSuggestions().get(k) : null;
            CriterionReadinessResult crr = readiness.getCriterionResults() != null ? readiness.getCriterionResults().get(k) : null;

            String suffStatus = crr != null && crr.getSufficiencyStatus() != null ? crr.getSufficiencyStatus().name() : null;
            String missingDataEx = crr != null && crr.getMissingCategories() != null && !crr.getMissingCategories().isEmpty() ? String.join(", ", crr.getMissingCategories()) : null;
            Map<String, Object> aiMetadata = new HashMap<>();
            BigDecimal aiConf = null;
            CriterionSuggestionReviewStatus revStatus = null;

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
                    .rawScore(v.getRawScore())
                    .inputMethod(v.getInputMethod())
                    .finalRationale(v.getExplanation())
                    .factualFindings(null)
                    .evidenceReferenceIds(v.getEvidenceIds() != null ? new ArrayList<>(v.getEvidenceIds()) : new ArrayList<>())
                    .dataSufficiencyStatus(suffStatus)
                    .missingDataExplanation(missingDataEx)
                    .suggestionReviewStatus(revStatus)
                    .staffEdited(v.getInputMethod() == CriterionInputMethod.AI_ASSISTED_EDITED)
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
                .referenceCompanyProfileId(draft.getReferenceProfileDocumentId())
                .referenceProfileVersion(draft.getReferenceProfileVersion())
                .evaluatedRole(CompanyRole.SUPPLIER)
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

        // 3. Create Outbox Event
        String eventId = UUID.randomUUID().toString();

        RoleEvaluationOutboxPayload payload = RoleEvaluationOutboxPayload.builder()
                .eventId(eventId)
                .eventType(RoleEvaluationOutboxEventType.SUPPLIER_EVALUATION_APPROVED.name())
                .evaluationId(draft.getId())
                .projectId(task.getProject().getId())
                .taskId(task.getId())
                .submissionId(submission.getId())
                .targetCompanyProfileId(draft.getTargetProfileDocumentId())
                .actorAccountId(accountId)
                .approvedVersionId(versionId)
                .approvedVersionNumber(nextVersion)
                .managerFeedback(request.getComment())
                .managerJustification(request.getComment())
                .aggregateCompletenessStatus(readiness.getAggregateCompletenessStatus())
                .occurredAt(LocalDateTime.now())
                .payloadVersion(1)
                .build();

        RoleEvaluationOutboxEvent event = RoleEvaluationOutboxEvent.builder()
                .id(eventId)
                .eventId(eventId)
                .evaluationId(draft.getId())
                .projectId(task.getProject().getId())
                .taskId(task.getId())
                .eventType(RoleEvaluationOutboxEventType.SUPPLIER_EVALUATION_APPROVED)
                .status(OutboxEventStatus.PENDING)
                .payload(payload)
                .payloadHash(RoleEvaluationOutboxPayloadHasher.hash(payload))
                .createdAt(LocalDateTime.now())
                .nextAttemptAt(LocalDateTime.now())
                .attemptCount(0)
                .build();

        mongoTemplate.insert(event);

        // 4. Draft Update (Mongo)
        Query query = new Query(Criteria.where("id").is(draft.getId())
                .and("status").is(RoleEvaluationStatus.IN_REVIEW)
                .and("workingRevisionNumber").is(draft.getSubmittedRevisionNumber())
                .and("sourceSnapshotHash").is(draft.getSubmittedSourceSnapshotHash())
                .and("optimisticVersion").is(draft.getOptimisticVersion()));

        Update update = new Update()
                .set("status", RoleEvaluationStatus.APPROVAL_PROCESSING)
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

        // Update local object to reflect the new state so the controller returns APPROVAL_PROCESSING
        draft.setStatus(RoleEvaluationStatus.APPROVAL_PROCESSING);
        draft.setCurrentApprovedVersionId(versionId);
        draft.setCurrentApprovedVersionNumber(nextVersion);
    }

    @Override
    @Transactional(transactionManager = "transactionManager")
    public void requestRevision(RoleEvaluationDraft draft, ProjectTask task, ProjectTaskSubmission submission, ReviewRoleEvaluationRequest request, Long accountId) {
        if (request.getComment() == null || request.getComment().isBlank()) {
            throw new IllegalArgumentException("Comment required for requesting revision");
        }

        Integer nextRevision = draft.getWorkingRevisionNumber() + 1;

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

        // Create Outbox Event
        String eventId = UUID.randomUUID().toString();

        RoleEvaluationOutboxPayload payload = RoleEvaluationOutboxPayload.builder()
                .eventId(eventId)
                .eventType(RoleEvaluationOutboxEventType.SUPPLIER_EVALUATION_REVISION_REQUESTED.name())
                .evaluationId(draft.getId())
                .projectId(task.getProject().getId())
                .taskId(task.getId())
                .submissionId(submission.getId())
                .targetCompanyProfileId(draft.getTargetProfileDocumentId())
                .actorAccountId(accountId)
                .managerFeedback(request.getComment())
                .occurredAt(LocalDateTime.now())
                .payloadVersion(1)
                .build();

        RoleEvaluationOutboxEvent event = RoleEvaluationOutboxEvent.builder()
                .id(eventId)
                .eventId(eventId)
                .evaluationId(draft.getId())
                .projectId(task.getProject().getId())
                .taskId(task.getId())
                .eventType(RoleEvaluationOutboxEventType.SUPPLIER_EVALUATION_REVISION_REQUESTED)
                .status(OutboxEventStatus.PENDING)
                .payload(payload)
                .payloadHash(RoleEvaluationOutboxPayloadHasher.hash(payload))
                .createdAt(LocalDateTime.now())
                .nextAttemptAt(LocalDateTime.now())
                .attemptCount(0)
                .build();

        mongoTemplate.insert(event);
    }

    @Override
    @Transactional(transactionManager = "transactionManager")
    public void reject(RoleEvaluationDraft draft, ProjectTask task, ProjectTaskSubmission submission, ReviewRoleEvaluationRequest request, Long accountId) {
        throw new UnsupportedOperationException("REJECT decision is not supported for SUPPLIER evaluations");
    }
}
