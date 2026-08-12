package com.apms.domain.score.service;

import com.apms.common.enums.OutboxEventStatus;
import com.apms.common.enums.SubmissionStatus;
import com.apms.common.enums.SystemRole;
import com.apms.common.enums.TaskStatus;
import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.company.enums.CompanyRole;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.ProjectTaskSubmission;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.domain.project.repository.sql.ProjectTaskSubmissionRepository;
import com.apms.domain.score.draft.CriterionInput;
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
import com.apms.domain.score.registry.CanonicalRoleCriteria;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class PartnerRoleEvaluationApprovalStrategy implements RoleEvaluationApprovalStrategy {

    private final MongoTemplate mongoTemplate;
    private final PartnerDataSufficiencyEvaluator sufficiencyEvaluator;
    private final ProjectSecurityEvaluator projectSecurityEvaluator;
    private final ProjectTaskRepository taskRepository;
    private final ProjectTaskSubmissionRepository submissionRepository;

    @Override
    public boolean supports(CompanyRole role) {
        return role == CompanyRole.PARTNER;
    }

    @Override
    public void approve(RoleEvaluationDraft draft, ProjectTask task, ProjectTaskSubmission submission, ReviewRoleEvaluationRequest request, Long accountId, String idempotencyKey) {

        if (!projectSecurityEvaluator.isManagerOrOwner(task.getProject().getId()) && !projectSecurityEvaluator.isManager(task.getProject().getId())) {
            throw new SecurityException("User is not authorized as Manager or Owner for this project");
        }
        SystemRole evaluatorRole = RoleEvaluationAuthorityResolver.currentEvaluatorRoleOrDefault(SystemRole.BUSINESS_DEVELOPMENT_MANAGER);
        boolean ownerFinal = evaluatorRole == SystemRole.BUSINESS_OWNER;

        // 1. Validation
        if (draft.getStatus() != RoleEvaluationStatus.IN_REVIEW) {
            throw new IllegalStateException("Draft must be IN_REVIEW to approve");
        }

        // Authorization would be checked in the caller (e.g. Manager check)

        if (draft.getStaleTargetProfile() || draft.getStaleReferenceProfile() || draft.getStaleRuleSet()) {
            throw new IllegalStateException("Draft contains stale versions. Cannot approve.");
        }

        validateStaffConfirmedCriteria(draft);
        EvaluationCompletenessStatus completenessStatus = EvaluationCompletenessStatus.COMPLETE;

        Integer nextVersion = (draft.getCurrentApprovedVersionNumber() == null ? 0 : draft.getCurrentApprovedVersionNumber()) + 1;
        String versionId = UUID.randomUUID().toString();

        Map<String, CriterionSnapshot> criteriaMap = new HashMap<>();
        draft.getCriterionInputs().forEach((k, v) -> {
            com.apms.domain.score.draft.AutomaticSuggestion suggestion = draft.getAutomaticSuggestions() != null ? draft.getAutomaticSuggestions().get(k) : null;

            String suffStatus = EvaluationCompletenessStatus.COMPLETE.name();
            String missingDataEx = null;
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
                    .rawScore(v.getRawScore())
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
                .status(ownerFinal ? RoleEvaluationStatus.FINAL : RoleEvaluationStatus.APPROVED)
                .evaluationPeriod(draft.getEvaluationPeriod())
                .criteria(criteriaMap)
                .sourceReferences(draft.getPinnedSourceReferences())
                .sourceSnapshotHash(draft.getSourceSnapshotHash())
                .submittedByAccountId(draft.getSubmittedByAccountId())
                .submittedAt(draft.getSubmittedAt())
                .approvedByAccountId(accountId)
                .approvedAt(LocalDateTime.now())
                .reviewComment(request.getComment())
                .evaluatorRole(evaluatorRole)
                .authoritative(ownerFinal)
                .completenessStatus(completenessStatus)
                .partialApprovalJustification(null)
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
                        .evaluatorRole(evaluatorRole)
                        .authoritative(ownerFinal)
                        .aggregateCompletenessStatus(completenessStatus)
                        .managerJustification(null)
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
                .set("status", ownerFinal ? RoleEvaluationStatus.FINAL : RoleEvaluationStatus.APPROVED)
                .set("active", false)
                .unset("activeDraftKey")
                .set("currentApprovedVersionId", versionId)
                .set("currentApprovedVersionNumber", nextVersion)
                .set("reviewedByAccountId", accountId)
                .set("reviewedAt", LocalDateTime.now())
                .set("reviewComment", request.getComment())
                .set("evaluatorRole", evaluatorRole)
                .set("ownerFinalized", ownerFinal)
                .inc("optimisticVersion", 1);

        long modified = mongoTemplate.updateFirst(query, update, RoleEvaluationDraft.class).getModifiedCount();
        if (modified == 0) {
            throw new IllegalStateException("Draft state changed or optimistic lock failed");
        }

        submission.setStatus(SubmissionStatus.APPROVED);
        submissionRepository.save(submission);

        task.setStatus(TaskStatus.DONE);
        task.setCompletedAt(LocalDateTime.now());
        taskRepository.save(task);
    }

    @Override
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
    public void reject(RoleEvaluationDraft draft, ProjectTask task, ProjectTaskSubmission submission, ReviewRoleEvaluationRequest request, Long accountId) {
        throw new UnsupportedOperationException("REJECT decision is not supported for PARTNER evaluations");
    }

    private void validateStaffConfirmedCriteria(RoleEvaluationDraft draft) {
        List<String> missing = CanonicalRoleCriteria.PARTNER_CRITERIA.stream()
                .filter(criterionKey -> !isCriterionComplete(draft, criterionKey))
                .toList();

        if (!missing.isEmpty()) {
            throw new BusinessValidationException("Cannot approve evaluation. Staff score, reason, and evidence are required for all 6 PARTNER criteria. Missing: " + String.join(", ", missing));
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
