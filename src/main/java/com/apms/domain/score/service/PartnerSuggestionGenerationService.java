package com.apms.domain.score.service;

import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.ai.dto.PartnerCriterionSuggestionResponse;
import com.apms.domain.score.draft.AutomaticSuggestion;
import com.apms.domain.score.draft.RoleEvaluationDraft;
import com.apms.domain.score.draft.ApprovedSourceReference;
import com.apms.domain.score.draft.EvidenceRecord;
import com.apms.domain.score.dto.draft.PartnerCriterionContext;
import com.apms.domain.score.enums.CriterionSuggestionMethod;
import com.apms.domain.score.enums.CriterionSuggestionReviewStatus;
import com.apms.domain.score.enums.CriterionSuggestionValidationStatus;
import com.apms.domain.score.enums.GenerationStatus;
import com.apms.domain.score.draft.PartnerSuggestionGenerationMetadata;
import com.apms.domain.score.repository.mongo.RoleEvaluationDraftRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import com.apms.common.exception.BusinessConflictException;

@Slf4j
@Service
@RequiredArgsConstructor
public class PartnerSuggestionGenerationService {

    private final RoleEvaluationDraftRepository draftRepository;
    private final PartnerEvaluationContextProvider contextProvider;
    private final PartnerAiSuggestionValidator validator;
    private final MongoTemplate mongoTemplate;
    private final com.apms.domain.ai.service.provider.PartnerAiPromptProvider promptProvider;
    private final com.apms.domain.ai.service.provider.PartnerCriterionSuggestionProvider aiProvider;
    private final PartnerDataSufficiencyEvaluator sufficiencyEvaluator;
    private final PotentialPartnerDataSufficiencyEvaluator potentialPartnerSufficiencyEvaluator;
    private final CustomerDataSufficiencyEvaluator customerSufficiencyEvaluator;

    public String generateSuggestion(String draftId, String criterionKey, String generationId) {
        RoleEvaluationDraft draft = draftRepository.findById(draftId)
                .orElseThrow(() -> new BusinessValidationException("Draft not found"));

        if (!hasPinnedSources(draft, criterionKey) && !hasSelectedEvidence(draft, criterionKey)) {
            return "NO_EVIDENCE_SELECTED";
        }

        String currentHash = draft.getSourceSnapshotHash();
        Set<String> allowedEvidenceReferenceIds = buildAllowedEvidenceReferenceIds(draft, criterionKey);
        List<String> sortedSourceRefIds = allowedEvidenceReferenceIds.stream()
                .sorted()
                .collect(Collectors.toList());

        List<PartnerSuggestionGenerationMetadata> metadataList = draft.getGenerationIdempotency()
                .getOrDefault(criterionKey, new ArrayList<>());

        PartnerSuggestionGenerationMetadata existingMeta = metadataList.stream()
                .filter(m -> generationId.equals(m.getGenerationId()))
                .findFirst()
                .orElse(null);

        if (existingMeta != null) {
            boolean sameSnapshot = currentHash != null && currentHash.equals(existingMeta.getSourceSnapshotHash());
            if (!sameSnapshot) {
                throw new BusinessConflictException("Idempotency conflict: generationId reused for different snapshot");
            }
            if (existingMeta.getValidationStatus() == GenerationStatus.APPLIED) {
                return "ALREADY_GENERATED";
            }
            if (existingMeta.getValidationStatus() == GenerationStatus.PENDING) {
                throw new BusinessConflictException("Generation is currently in progress");
            }
            // If FAILED or STALE, we can retry, so we proceed
        }

        // Reserve generation metadata
        PartnerSuggestionGenerationMetadata newMeta = PartnerSuggestionGenerationMetadata.builder()
                .generationId(generationId)
                .criterionKey(criterionKey)
                .draftRevisionNumber(draft.getWorkingRevisionNumber())
                .sourceSnapshotHash(currentHash)
                .sortedSourceReferenceIds(sortedSourceRefIds)
                .validationStatus(GenerationStatus.PENDING)
                .generatedAt(LocalDateTime.now())
                .build();

        metadataList.removeIf(m -> m.getGenerationId().equals(generationId));
        metadataList.add(newMeta);

        // Bounded retention policy: max 10 entries per criterion
        if (metadataList.size() > 10) {
            metadataList = metadataList.subList(metadataList.size() - 10, metadataList.size());
        }
        draft.getGenerationIdempotency().put(criterionKey, metadataList);

        // CAS reservation
        Query reserveQuery = new Query(Criteria.where("_id").is(draftId)
                .and("workingRevisionNumber").is(draft.getWorkingRevisionNumber())
                .and("optimisticVersion").is(draft.getOptimisticVersion()));
        Update reserveUpdate = new Update()
                .set("generationIdempotency", draft.getGenerationIdempotency())
                .inc("optimisticVersion", 1)
                .set("updatedAt", LocalDateTime.now());

        var reserveResult = mongoTemplate.updateFirst(reserveQuery, reserveUpdate, RoleEvaluationDraft.class);
        if (reserveResult.getMatchedCount() == 0) {
            throw new BusinessConflictException("Failed to reserve generation metadata due to concurrent modification");
        }

        // Build context and call AI
        PartnerCriterionContext context = contextProvider.buildContext(draft, criterionKey);
        String promptTemplate = promptProvider.getPromptTemplate(criterionKey);
        String aiResponseJson = aiProvider.generateSuggestionJson(context, promptTemplate);
        PartnerCriterionSuggestionResponse validatedResponse = validator.validateAndMap(aiResponseJson, criterionKey, allowedEvidenceReferenceIds);

        // Reload draft to apply results
        RoleEvaluationDraft reloadedDraft = draftRepository.findById(draftId)
                .orElseThrow(() -> new BusinessValidationException("Draft not found"));

        boolean isStale = !draft.getWorkingRevisionNumber().equals(reloadedDraft.getWorkingRevisionNumber()) ||
                          !Objects.equals(currentHash, reloadedDraft.getSourceSnapshotHash());

        List<PartnerSuggestionGenerationMetadata> reloadedMetadata = reloadedDraft.getGenerationIdempotency()
                .getOrDefault(criterionKey, new ArrayList<>());

        PartnerSuggestionGenerationMetadata targetMeta = reloadedMetadata.stream()
                .filter(m -> generationId.equals(m.getGenerationId()))
                .findFirst()
                .orElse(null);

        if (targetMeta == null) {
             throw new BusinessConflictException("Reserved metadata lost");
        }

        targetMeta.setAppliedAt(LocalDateTime.now());
        if (isStale) {
            targetMeta.setValidationStatus(GenerationStatus.STALE);
        } else {
            targetMeta.setValidationStatus(GenerationStatus.APPLIED);

            AutomaticSuggestion suggestion = new AutomaticSuggestion();
            suggestion.setGenerationId(generationId);
            suggestion.setCriterionKey(criterionKey);
            suggestion.setMethod(CriterionSuggestionMethod.AI_ASSISTED);
            suggestion.setGeneratedAt(LocalDateTime.now());
            suggestion.setSourceSnapshotHash(currentHash);
            suggestion.setDraftRevisionNumber(draft.getWorkingRevisionNumber());
            suggestion.setValidationStatus(CriterionSuggestionValidationStatus.PASS);
            suggestion.setSuggestedRawScore(validatedResponse.getSuggestedRawScore());
            suggestion.setSuggestionRationale(validatedResponse.getRationale());
            suggestion.setExplanation(validatedResponse.getRationale());
            suggestion.setConfidence(validatedResponse.getConfidence());
            suggestion.setEvidenceIds(validatedResponse.getEvidenceReferenceIds());
            suggestion.setMissingData(validatedResponse.getMissingDataNotes() != null ? validatedResponse.getMissingDataNotes() : new ArrayList<>());
            suggestion.setReviewStatus(CriterionSuggestionReviewStatus.PENDING);

            reloadedDraft.getAutomaticSuggestions().put(criterionKey, suggestion);
        }

        // CAS Application
        Query applyQuery = new Query(Criteria.where("_id").is(draftId)
                .and("workingRevisionNumber").is(reloadedDraft.getWorkingRevisionNumber())
                .and("optimisticVersion").is(reloadedDraft.getOptimisticVersion()));
        Update applyUpdate = new Update()
                .set("generationIdempotency", reloadedDraft.getGenerationIdempotency())
                .set("automaticSuggestions", reloadedDraft.getAutomaticSuggestions())
                .inc("optimisticVersion", 1)
                .set("updatedAt", LocalDateTime.now());

        var applyResult = mongoTemplate.updateFirst(applyQuery, applyUpdate, RoleEvaluationDraft.class);
        if (applyResult.getMatchedCount() == 0) {
            throw new BusinessConflictException("Failed to apply generation results due to concurrent modification");
        }

        return isStale ? "STALE_GENERATION" : "GENERATED";
    }

    private boolean hasPinnedSources(RoleEvaluationDraft draft, String criterionKey) {
        return draft.getPinnedSourceReferences() != null
                && draft.getPinnedSourceReferences().stream()
                .anyMatch(ref -> ref.getCriterionKey() == null || ref.getCriterionKey().equals(criterionKey));
    }

    private boolean hasSelectedEvidence(RoleEvaluationDraft draft, String criterionKey) {
        return draft.getCriterionEvidence() != null
                && draft.getCriterionEvidence().getOrDefault(criterionKey, List.of()).stream()
                .anyMatch(evidence -> evidence.getEvidenceId() != null
                        && evidence.getRawDocumentId() != null
                        && !evidence.getRawDocumentId().isBlank());
    }

    private Set<String> buildAllowedEvidenceReferenceIds(RoleEvaluationDraft draft, String criterionKey) {
        Set<String> ids = new LinkedHashSet<>();
        if (draft.getPinnedSourceReferences() != null) {
            draft.getPinnedSourceReferences().stream()
                    .filter(ref -> ref.getCriterionKey() == null || ref.getCriterionKey().equals(criterionKey))
                    .map(ApprovedSourceReference::getReferenceId)
                    .filter(Objects::nonNull)
                    .forEach(ids::add);
        }
        if (draft.getCriterionEvidence() != null) {
            draft.getCriterionEvidence().getOrDefault(criterionKey, List.of()).stream()
                    .map(EvidenceRecord::getEvidenceId)
                    .filter(Objects::nonNull)
                    .forEach(ids::add);
        }
        return ids;
    }

}
