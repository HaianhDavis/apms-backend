package com.apms.domain.score.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.TaskType;
import com.apms.config.OwnerOrganizationProperties;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.company.enums.CompanyRole;
import com.apms.common.enums.RelationshipType;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.CompanyProfileVersion;
import com.apms.domain.project.Project;
import com.apms.domain.project.ProjectTask;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.project.repository.sql.ProjectTaskRepository;
import com.apms.domain.score.RoleScoreRuleSet;
import com.apms.domain.score.draft.AutomaticSuggestion;
import com.apms.domain.score.draft.CriterionInput;
import com.apms.domain.score.draft.EvidenceRecord;
import com.apms.domain.score.draft.RoleEvaluationDraft;
import com.apms.domain.score.dto.RoleEvaluationCalculationRequest;
import com.apms.domain.score.dto.RoleEvaluationCalculationResult;
import com.apms.domain.score.dto.draft.*;
import com.apms.domain.score.engine.RoleScoringEngine;
import com.apms.domain.score.enums.CriterionInputMethod;
import com.apms.domain.score.enums.EvaluationCompletenessStatus;
import com.apms.domain.score.enums.RoleEvaluationStatus;
import com.apms.domain.score.enums.CriterionSuggestionValidationStatus;
import com.apms.domain.score.enums.CriterionSuggestionReviewStatus;
import com.apms.domain.score.repository.sql.RoleScoreRuleSetRepository;
import com.apms.domain.score.repository.mongo.RoleEvaluationDraftRepository;
import com.apms.domain.score.dto.draft.SourceSelectionRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.apms.common.exception.BusinessConflictException;
import com.apms.domain.score.enums.EvaluationCompletenessStatus;
import com.apms.domain.score.draft.ApprovedSourceReference;
import com.apms.domain.score.draft.SourceSnapshotHasher;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class RoleEvaluationDraftService {

    private final RoleEvaluationDraftRepository draftRepository;
    private final ProjectRepository projectRepository;
    private final ProjectTaskRepository taskRepository;
    private final RoleScoreRuleSetRepository ruleSetRepository;
    private final MongoTemplate mongoTemplate;

    private final CompanyProfileIdentifierResolver identifierResolver;
    private final OwnerOrganizationProperties ownerProperties;

    private final CompetitorComparisonService comparisonService;
    private final RoleScoringEngine scoringEngine;
    private final RelationshipTypeToCompanyRoleMapper roleMapper;
    private final AuditLogService auditLogService;
    private final CriterionSuggestionValidator suggestionValidator;
    private final SourcePinningValidator sourcePinningValidator;

    @Transactional
    public RoleEvaluationDraftResponse createDraft(Long projectId, Long taskId, CreateRoleEvaluationDraftRequest request, Long accountId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        ProjectTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));

        if (!task.getProject().getId().equals(projectId)) {
            throw new IllegalArgumentException("Task does not belong to Project");
        }

        if (task.getTaskType() != TaskType.ROLE_EVALUATION) {
            throw new IllegalArgumentException("Task type must be ROLE_EVALUATION");
        }

        if (project.getTargetRelationshipType() != RelationshipType.COMPETITOR_OF) {
            throw new IllegalArgumentException("Phase 2B only supports COMPETITOR_OF");
        }

        CompanyRole evaluatedRole = roleMapper.map(project.getTargetRelationshipType());

        String targetCompanyId = project.getTargetCompanyProfileId();
        CompanyProfile targetProfile = identifierResolver.resolveTargetProfile(targetCompanyId);

        if (targetProfile.getCompanyId().equals(ownerProperties.getCompanyProfileId()) ||
            targetProfile.getId().equals(ownerProperties.getCompanyProfileId())) {
            throw new IllegalArgumentException("Target company cannot be the Owner organization");
        }

        if (!"APPROVED".equals(targetProfile.getReviewStatus())) {
            throw new IllegalArgumentException("Target profile is not APPROVED");
        }

        CompanyProfileVersion targetVersion = identifierResolver.resolveVersion(targetProfile.getId(), targetProfile.getVersion());

        CompanyProfile fptProfile = identifierResolver.resolveProfileByDocumentId(ownerProperties.getCompanyProfileId());

        if (!"APPROVED".equals(fptProfile.getReviewStatus())) {
            throw new IllegalArgumentException("Owner profile is not APPROVED");
        }

        CompanyProfileVersion fptVersion = identifierResolver.resolveVersion(fptProfile.getId(), fptProfile.getVersion());

        RoleScoreRuleSet ruleSet = ruleSetRepository.findByEvaluatedRoleAndActiveTrue(evaluatedRole)
                .orElseThrow(() -> new IllegalArgumentException("No active rule set found for role: " + evaluatedRole));

        String activeDraftKey = projectId + ":" + taskId + ":" + evaluatedRole;
        if (draftRepository.existsByActiveDraftKey(activeDraftKey)) {
            throw new IllegalStateException("An active draft already exists for this task and role.");
        }

        RoleEvaluationDraft draft = RoleEvaluationDraft.builder()
                .projectId(projectId)
                .taskId(taskId)
                .targetCompanyId(targetCompanyId)
                .targetProfileDocumentId(targetProfile.getId())
                .targetProfileVersion(targetProfile.getVersion())
                .referenceCompanyId(fptProfile.getCompanyId())
                .referenceProfileDocumentId(fptProfile.getId())
                .referenceProfileVersion(fptProfile.getVersion())
                .evaluatedRole(evaluatedRole)
                .ruleSetVersion(ruleSet.getRuleSetVersion())
                .weightVersion(ruleSet.getWeightVersion()) // from Phase 1, weights match rule version if active
                .status(RoleEvaluationStatus.DRAFT)
                .staleTargetProfile(false)
                .staleReferenceProfile(false)
                .staleRuleSet(false)
                .active(true)
                .activeDraftKey(activeDraftKey)
                .createdByAccountId(accountId)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        draft = draftRepository.save(draft);

        auditLogService.log(accountId, AuditAction.ROLE_EVALUATION_DRAFT_CREATED, "ROLE_EVALUATION_DRAFT", draft.getId(),
                "Created draft for project=" + projectId + " task=" + taskId + " role=" + evaluatedRole);

        return mapToResponse(draft);
    }

    public RoleEvaluationDraftResponse getDraft(String draftId) {
        RoleEvaluationDraft draft = draftRepository.findById(draftId)
                .orElseThrow(() -> new IllegalArgumentException("Draft not found"));
        return mapToResponse(draft);
    }

    public RoleEvaluationDraft getRawDraft(String draftId) {
        return draftRepository.findById(draftId)
                .orElseThrow(() -> new IllegalArgumentException("Draft not found"));
    }

    public RoleEvaluationDraftResponse updateCriterionInput(String draftId, String criterionKey, UpdateCriterionInputRequest request, Long accountId) {
        RoleEvaluationDraft draft = draftRepository.findById(draftId)
                .orElseThrow(() -> new IllegalArgumentException("Draft not found"));

        if (draft.getStatus() != RoleEvaluationStatus.DRAFT && draft.getStatus() != RoleEvaluationStatus.REVISION_REQUIRED) {
            throw new IllegalStateException("Draft cannot be edited in current state: " + draft.getStatus());
        }

        CriterionInput input = draft.getCriterionInputs().getOrDefault(criterionKey, new CriterionInput());
        input.setCriterionKey(criterionKey);
        input.setRawScore(request.getRawScore());
        input.setExplanation(request.getExplanation());
        if (request.getEvidenceIds() != null) {
            input.setEvidenceIds(request.getEvidenceIds());
        }

        if (request.getInputMethod() != null) {
            // Staff cannot force AUTOMATIC_PROPOSAL without accepting it via the proper endpoint
            if (request.getInputMethod() == CriterionInputMethod.AUTOMATIC_PROPOSAL) {
                if (input.getInputMethod() != CriterionInputMethod.AUTOMATIC_PROPOSAL) {
                    throw new IllegalArgumentException("AUTOMATIC_PROPOSAL must be set via acceptance endpoint");
                }
            }
            input.setInputMethod(request.getInputMethod());
        } else if (input.getInputMethod() == null) {
            input.setInputMethod(CriterionInputMethod.MANUAL_REVIEWED);
        }

        input.setPreparedByAccountId(accountId);
        input.setPreparedAt(LocalDateTime.now());

        // Reset manager confirmed on edit
        input.setManagerConfirmed(false);

        draft.getCriterionInputs().put(criterionKey, input);
        draft.setUpdatedAt(LocalDateTime.now());

        auditLogService.log(accountId, AuditAction.ROLE_EVALUATION_CRITERION_UPDATED, "ROLE_EVALUATION_DRAFT", draftId,
                "Updated criterion=" + criterionKey);

        return mapToResponse(draftRepository.save(draft));
    }

    public RoleEvaluationDraftResponse addEvidence(String draftId, CreateEvidenceRequest request, Long accountId) {
        RoleEvaluationDraft draft = draftRepository.findById(draftId)
                .orElseThrow(() -> new IllegalArgumentException("Draft not found"));

        if (draft.getStatus() != RoleEvaluationStatus.DRAFT && draft.getStatus() != RoleEvaluationStatus.REVISION_REQUIRED) {
            throw new IllegalStateException("Draft cannot be edited in current state");
        }

        String evidenceId = UUID.randomUUID().toString();

        EvidenceRecord evidence = EvidenceRecord.builder()
                .evidenceId(evidenceId)
                .criterionKey(request.getCriterionKey())
                .sourceType(request.getSourceType())
                .rawDocumentId(request.getRawDocumentId())
                .companyId(request.getCompanyId())
                .profileDocumentId(request.getProfileDocumentId())
                .profileVersion(request.getProfileVersion())
                .externalUrl(request.getExternalUrl())
                .evidenceDate(request.getEvidenceDate())
                .extractedFieldPath(request.getExtractedFieldPath())
                .evidenceCategory(request.getEvidenceCategory())
                .reliability(request.getReliability())
                .note(request.getNote())
                .preparedByAccountId(accountId)
                .preparedAt(LocalDateTime.now())
                .build();

        List<EvidenceRecord> list = draft.getCriterionEvidence().computeIfAbsent(request.getCriterionKey(), k -> new ArrayList<>());
        list.add(evidence);

        draft.setUpdatedAt(LocalDateTime.now());
        auditLogService.log(accountId, AuditAction.ROLE_EVALUATION_EVIDENCE_ADDED, "ROLE_EVALUATION_DRAFT", draftId,
                "Added evidence for criterion=" + request.getCriterionKey());
        return mapToResponse(draftRepository.save(draft));
    }

    public RoleEvaluationDraftResponse suggestProductMarketOverlap(String draftId) {
        RoleEvaluationDraft draft = draftRepository.findById(draftId)
                .orElseThrow(() -> new IllegalArgumentException("Draft not found"));

        CompanyProfile target = identifierResolver.resolveProfileByDocumentId(draft.getTargetProfileDocumentId());
        CompanyProfile reference = identifierResolver.resolveProfileByDocumentId(draft.getReferenceProfileDocumentId());

        AutomaticSuggestion suggestion = comparisonService.suggestProductMarketOverlap(target, reference, com.apms.domain.score.enums.OverlapSuggestionMode.LEGACY_PARTIAL);

        draft.getAutomaticSuggestions().put("productMarketOverlapScore", suggestion);
        draft.setUpdatedAt(LocalDateTime.now());

        // Note: no accountId available in this read-only suggestion endpoint
        // Audit is fire-and-forget; do not block the response

        return mapToResponse(draftRepository.save(draft));
    }

    public RoleEvaluationDraftResponse acceptAutomaticSuggestion(String draftId, AcceptAutomaticSuggestionRequest request, Long accountId) {
        return acceptCriterionSuggestion(draftId, "productMarketOverlapScore", request, accountId);
    }

    public RoleEvaluationDraftResponse acceptCriterionSuggestion(String draftId, String criterionKey, AcceptAutomaticSuggestionRequest request, Long accountId) {
        RoleEvaluationDraft draft = draftRepository.findById(draftId)
                .orElseThrow(() -> new IllegalArgumentException("Draft not found"));

        if (draft.getStatus() != RoleEvaluationStatus.DRAFT && draft.getStatus() != RoleEvaluationStatus.REVISION_REQUIRED) {
            throw new IllegalStateException("Draft cannot be edited in current state");
        }


        AutomaticSuggestion suggestion = draft.getAutomaticSuggestions().get(criterionKey);
        if (suggestion == null) {
            throw new IllegalArgumentException("No automatic suggestion exists for " + criterionKey);
        }

        CriterionSuggestionValidator.SuggestionValidationResult validationResult = suggestionValidator.validate(criterionKey, suggestion, draft);

        if (validationResult.getStatus() == CriterionSuggestionValidationStatus.FAIL ||
            validationResult.isBlocking() ||
            suggestion.getReviewStatus() == CriterionSuggestionReviewStatus.NEEDS_MORE_DATA) {
            throw new IllegalArgumentException("Cannot accept suggestion with status FAIL, blocking WARNING, or NEEDS_MORE_DATA");
        }

        if (validationResult.getStatus() == CriterionSuggestionValidationStatus.WARNING && !validationResult.isBlocking()) {
            if (request.getExplanation() == null || request.getExplanation().isBlank()) {
                throw new IllegalArgumentException("Accepting a non-blocking WARNING suggestion requires a review comment (explanation)");
            }
        }

        if (suggestion.getSuggestedRawScore() == null) {
            throw new IllegalArgumentException("Cannot accept suggestion with null score due to insufficient coverage.");
        }

        suggestion.setAccepted(true);
        suggestion.setReviewStatus(CriterionSuggestionReviewStatus.ACCEPTED);
        suggestion.setReviewedByAccountId(accountId);
        suggestion.setReviewedAt(LocalDateTime.now());
        if (request.getExplanation() != null && !request.getExplanation().isBlank()) {
            suggestion.setReviewComment(request.getExplanation());
        }

        CriterionInput input = draft.getCriterionInputs().getOrDefault(criterionKey, new CriterionInput());
        input.setCriterionKey(criterionKey);
        input.setRawScore(suggestion.getSuggestedRawScore());
        input.setInputMethod(CriterionInputMethod.AUTOMATIC_PROPOSAL);

        String explanation = suggestion.getEffectiveExplanation();
        if (request.getExplanation() != null && !request.getExplanation().isBlank()) {
            explanation += " | Staff note: " + request.getExplanation();
        }
        input.setExplanation(explanation);

        input.setPreparedByAccountId(accountId);
        input.setPreparedAt(LocalDateTime.now());
        input.setManagerConfirmed(false);

        draft.getCriterionInputs().put(criterionKey, input);
        draft.setUpdatedAt(LocalDateTime.now());

        auditLogService.log(accountId, AuditAction.ROLE_EVALUATION_SUGGESTION_ACCEPTED, "ROLE_EVALUATION_DRAFT", draftId,
                "Accepted criterion suggestion for " + criterionKey);

        return mapToResponse(draftRepository.save(draft));
    }

    public RoleEvaluationDraftResponse editCriterionSuggestion(String draftId, String criterionKey, EditCriterionSuggestionRequest request, Long accountId) {
        RoleEvaluationDraft draft = draftRepository.findById(draftId)
                .orElseThrow(() -> new IllegalArgumentException("Draft not found"));

        if (draft.getStatus() != RoleEvaluationStatus.DRAFT && draft.getStatus() != RoleEvaluationStatus.REVISION_REQUIRED) {
            throw new IllegalStateException("Draft cannot be edited in current state");
        }

        if (request.getRawScore() == null || request.getRawScore().compareTo(BigDecimal.ZERO) < 0 || request.getRawScore().compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalArgumentException("Edited score must be between 0 and 100");
        }

        if (request.getOverrideReason() == null || request.getOverrideReason().isBlank()) {
            throw new IllegalArgumentException("Edit requires an override reason");
        }

        AutomaticSuggestion suggestion = draft.getAutomaticSuggestions().get(criterionKey);
        if (suggestion == null) {
            throw new IllegalArgumentException("No automatic suggestion exists for " + criterionKey);
        }

        CriterionSuggestionValidator.SuggestionValidationResult validationResult = suggestionValidator.validate(criterionKey, suggestion, draft);

        suggestion.setReviewStatus(CriterionSuggestionReviewStatus.EDITED);
        suggestion.setReviewedByAccountId(accountId);
        suggestion.setReviewedAt(LocalDateTime.now());
        suggestion.setReviewComment(request.getOverrideReason());

        CriterionInput input = draft.getCriterionInputs().getOrDefault(criterionKey, new CriterionInput());
        input.setCriterionKey(criterionKey);
        input.setPreviousValue(suggestion.getSuggestedRawScore());
        input.setRawScore(request.getRawScore());
        input.setOverrideReason(request.getOverrideReason());
        input.setInputMethod(CriterionInputMethod.MANUAL_OVERRIDE);

        String explanation = suggestion.getEffectiveExplanation();
        if (explanation == null) explanation = "";
        explanation += " | Manual override reason: " + request.getOverrideReason();
        input.setExplanation(explanation);

        input.setPreparedByAccountId(accountId);
        input.setPreparedAt(LocalDateTime.now());
        input.setManagerConfirmed(false);

        draft.getCriterionInputs().put(criterionKey, input);
        draft.setUpdatedAt(LocalDateTime.now());

        auditLogService.log(accountId, AuditAction.ROLE_EVALUATION_CRITERION_UPDATED, "ROLE_EVALUATION_DRAFT", draftId,
                "Edited criterion suggestion for " + criterionKey);

        return mapToResponse(draftRepository.save(draft));
    }

    public RoleEvaluationDraftResponse rejectCriterionSuggestion(String draftId, String criterionKey, RejectCriterionSuggestionRequest request, Long accountId) {
        RoleEvaluationDraft draft = draftRepository.findById(draftId)
                .orElseThrow(() -> new IllegalArgumentException("Draft not found"));

        if (draft.getStatus() != RoleEvaluationStatus.DRAFT && draft.getStatus() != RoleEvaluationStatus.REVISION_REQUIRED) {
            throw new IllegalStateException("Draft cannot be edited in current state");
        }

        if (request.getReviewComment() == null || request.getReviewComment().isBlank()) {
            throw new IllegalArgumentException("Rejection requires a review comment");
        }

        AutomaticSuggestion suggestion = draft.getAutomaticSuggestions().get(criterionKey);
        if (suggestion == null) {
            throw new IllegalArgumentException("No automatic suggestion exists for " + criterionKey);
        }

        CriterionSuggestionValidator.SuggestionValidationResult validationResult = suggestionValidator.validate(criterionKey, suggestion, draft);

        suggestion.setReviewStatus(CriterionSuggestionReviewStatus.REJECTED);
        suggestion.setReviewedByAccountId(accountId);
        suggestion.setReviewedAt(LocalDateTime.now());
        suggestion.setReviewComment(request.getReviewComment());

        // Remove AUTOMATIC_PROPOSAL input if it exists
        CriterionInput existingInput = draft.getCriterionInputs().get(criterionKey);
        if (existingInput != null && existingInput.getInputMethod() == CriterionInputMethod.AUTOMATIC_PROPOSAL) {
            draft.getCriterionInputs().remove(criterionKey);
        }

        draft.setUpdatedAt(LocalDateTime.now());

        auditLogService.log(accountId, AuditAction.ROLE_EVALUATION_CRITERION_UPDATED, "ROLE_EVALUATION_DRAFT", draftId,
                "Rejected criterion suggestion for " + criterionKey);

        return mapToResponse(draftRepository.save(draft));
    }

    public RoleEvaluationDraftResponse markSuggestionNeedsMoreData(String draftId, String criterionKey, NeedsMoreDataCriterionSuggestionRequest request, Long accountId) {
        RoleEvaluationDraft draft = draftRepository.findById(draftId)
                .orElseThrow(() -> new IllegalArgumentException("Draft not found"));

        if (draft.getStatus() != RoleEvaluationStatus.DRAFT && draft.getStatus() != RoleEvaluationStatus.REVISION_REQUIRED) {
            throw new IllegalStateException("Draft cannot be edited in current state");
        }

        AutomaticSuggestion suggestion = draft.getAutomaticSuggestions().get(criterionKey);
        if (suggestion == null) {
            throw new IllegalArgumentException("No automatic suggestion exists for " + criterionKey);
        }

        if (suggestion.getSuggestedRawScore() != null) {
            throw new IllegalArgumentException("Cannot mark as NEEDS_MORE_DATA when a score is suggested. Must be null.");
        }

        if (request.getMissingData() == null || request.getMissingData().isEmpty()) {
            throw new IllegalArgumentException("missingData must be non-empty to mark as NEEDS_MORE_DATA");
        }

        suggestion.setReviewStatus(CriterionSuggestionReviewStatus.NEEDS_MORE_DATA);
        suggestion.setReviewedByAccountId(accountId);
        suggestion.setReviewedAt(LocalDateTime.now());
        suggestion.setReviewComment(request.getReviewComment());
        suggestion.setMissingData(request.getMissingData());

        CriterionSuggestionValidator.SuggestionValidationResult validationResult = suggestionValidator.validate(criterionKey, suggestion, draft);

        draft.setUpdatedAt(LocalDateTime.now());

        auditLogService.log(accountId, AuditAction.ROLE_EVALUATION_CRITERION_UPDATED, "ROLE_EVALUATION_DRAFT", draftId,
                "Marked suggestion as NEEDS_MORE_DATA for " + criterionKey);

        return mapToResponse(draftRepository.save(draft));
    }

    public RoleEvaluationPreviewResponse calculatePreview(String draftId) {
        RoleEvaluationDraft draft = draftRepository.findById(draftId)
                .orElseThrow(() -> new IllegalArgumentException("Draft not found"));

        RoleEvaluationCalculationRequest request = new RoleEvaluationCalculationRequest();
        request.setEvaluatedRole(draft.getEvaluatedRole());

        Map<String, BigDecimal> scores = new HashMap<>();
        for (Map.Entry<String, CriterionInput> entry : draft.getCriterionInputs().entrySet()) {
            if (entry.getValue().getRawScore() != null) {
                scores.put(entry.getKey(), entry.getValue().getRawScore());
            }
        }
        request.setCriterionScores(new LinkedHashMap<>(scores));

        RoleScoreRuleSet ruleSet = ruleSetRepository.findByEvaluatedRoleAndActiveTrue(draft.getEvaluatedRole())
                .orElseThrow(() -> new IllegalStateException("Active rule set not found for preview"));

        RoleEvaluationCalculationResult result = scoringEngine.calculate(request);

        RoleEvaluationPreviewResponse response = new RoleEvaluationPreviewResponse();
        response.setCriterionScores(new LinkedHashMap<>(result.getCriterionScores()));
        response.setNormalizedCriterionScores(new LinkedHashMap<>(result.getNormalizedCriterionScores()));
        response.setCompletenessStatus(result.getCompletenessStatus());
        response.setMissingCriteria(result.getMissingCriteria());

        if (result.getCompletenessStatus() == EvaluationCompletenessStatus.COMPLETE) {
            response.setPreviewOverallScore(result.getOverallScore());
        } else {
            response.setPreviewOverallScore(null);
        }

        response.setWarnings(new ArrayList<>());
        return response;
    }

    // Map to response DTO safely
    private RoleEvaluationDraftResponse mapToResponse(RoleEvaluationDraft draft) {
        RoleEvaluationDraftResponse response = new RoleEvaluationDraftResponse();
        response.setId(draft.getId());
        response.setProjectId(draft.getProjectId());
        response.setTaskId(draft.getTaskId());
        response.setTargetCompanyId(draft.getTargetCompanyId());
        response.setTargetProfileDocumentId(draft.getTargetProfileDocumentId());
        response.setTargetProfileVersion(draft.getTargetProfileVersion());
        response.setReferenceCompanyId(draft.getReferenceCompanyId());
        response.setReferenceProfileDocumentId(draft.getReferenceProfileDocumentId());
        response.setReferenceProfileVersion(draft.getReferenceProfileVersion());
        response.setEvaluatedRole(draft.getEvaluatedRole());
        response.setRuleSetVersion(draft.getRuleSetVersion());
        response.setWeightVersion(draft.getWeightVersion());
        response.setStatus(draft.getStatus());

        response.setCriterionInputs(draft.getCriterionInputs());
        response.setAutomaticSuggestions(draft.getAutomaticSuggestions());
        response.setCriterionEvidence(draft.getCriterionEvidence());

        response.setStaleTargetProfile(draft.getStaleTargetProfile());
        response.setStaleReferenceProfile(draft.getStaleReferenceProfile());
        response.setStaleRuleSet(draft.getStaleRuleSet());
        response.setActive(draft.getActive());
        response.setApprovedSnapshotId(draft.getApprovedSnapshotId());
        response.setCreatedByAccountId(draft.getCreatedByAccountId());
        response.setCreatedAt(draft.getCreatedAt());
        response.setUpdatedAt(draft.getUpdatedAt());
        response.setSubmittedByAccountId(draft.getSubmittedByAccountId());
        response.setSubmittedAt(draft.getSubmittedAt());
        response.setReviewedByAccountId(draft.getReviewedByAccountId());
        response.setReviewedAt(draft.getReviewedAt());
        response.setReviewComment(draft.getReviewComment());

        return response;
    }

    public void pinSourceReferences(String draftId, List<SourceSelectionRequest> requests, Integer expectedWorkingRevisionNumber, Long expectedOptimisticVersion) {
        RoleEvaluationDraft draft = draftRepository.findById(draftId)
                .orElseThrow(() -> new IllegalArgumentException("Draft not found"));
        
        if (draft.getStatus() != RoleEvaluationStatus.DRAFT && draft.getStatus() != RoleEvaluationStatus.REVISION_REQUIRED) {
            throw new IllegalStateException("Draft cannot be edited in current state");
        }
        
        List<ApprovedSourceReference> verifiedReferences = sourcePinningValidator.validateAndBuildReferences(requests, draft);
        String snapshotHash = SourceSnapshotHasher.hash(verifiedReferences);
        
        Query query = new Query(Criteria.where("_id").is(draftId)
                .and("workingRevisionNumber").is(expectedWorkingRevisionNumber)
                .and("optimisticVersion").is(expectedOptimisticVersion));
                
        Update update = new Update()
                .set("pinnedSourceReferences", verifiedReferences)
                .set("sourceSnapshotHash", snapshotHash)
                .inc("workingRevisionNumber", 1)
                .inc("optimisticVersion", 1)
                .set("updatedAt", LocalDateTime.now());
                
        var result = mongoTemplate.updateFirst(query, update, RoleEvaluationDraft.class);
        if (result.getMatchedCount() == 0) {
            throw new BusinessConflictException("Concurrent modification detected. Draft was modified by another writer.");
        }
    }
}
