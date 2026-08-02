package com.apms.domain.score.controller;

import com.apms.common.response.ApiResponse;
import com.apms.domain.company.enums.CompanyRole;
import com.apms.domain.document.RawDocument;
import com.apms.domain.document.repository.mongo.RawDocumentRepository;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import com.apms.domain.profile.service.OwnerOrganizationService;
import com.apms.domain.score.RoleScoreRuleSet;
import com.apms.domain.score.ScoreSnapshot;
import com.apms.domain.score.draft.CriterionSnapshot;
import com.apms.domain.score.draft.EvidenceRecord;
import com.apms.domain.score.draft.RoleEvaluationDraft;
import com.apms.domain.score.draft.RoleEvaluationVersion;
import com.apms.domain.score.dto.RoleEvaluationVersionResponse;
import com.apms.domain.score.dto.RoleScoreRuleSetResponse;
import com.apms.domain.score.dto.RoleScoreSnapshotResponse;
import com.apms.domain.score.repository.mongo.RoleEvaluationVersionRepository;
import com.apms.domain.score.repository.mongo.RoleEvaluationDraftRepository;
import com.apms.domain.score.repository.sql.RoleScoreRuleSetRepository;
import com.apms.domain.score.repository.sql.ScoreSnapshotRepository;
import com.apms.domain.score.service.CanonicalScoreJsonMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class RoleScoreController {

    private final ScoreSnapshotRepository scoreSnapshotRepository;
    private final RoleScoreRuleSetRepository ruleSetRepository;
    private final RoleEvaluationVersionRepository roleEvaluationVersionRepository;
    private final RoleEvaluationDraftRepository roleEvaluationDraftRepository;
    private final RawDocumentRepository rawDocumentRepository;
    private final CompanyProfileRepository companyProfileRepository;
    private final CanonicalScoreJsonMapper jsonMapper;
    private final OwnerOrganizationService ownerOrganizationService;

    @GetMapping("/profiles/{companyProfileId}/role-scores")
    public ResponseEntity<ApiResponse<List<RoleScoreSnapshotResponse>>> getRoleScores(
            @PathVariable String companyProfileId,
            @RequestParam(required = false) CompanyRole role) {

        if (companyProfileId.equals(ownerOrganizationService.getOwnerCompanyId())) {
            throw new IllegalArgumentException("The Owner Organization cannot be evaluated as a target company.");
        }

        List<ScoreSnapshot> snapshots;
        if (role != null) {
            snapshots = scoreSnapshotRepository.findByTargetCompanyProfileIdAndEvaluatedRoleOrderByCalculatedAtDesc(companyProfileId, role);
        } else {
            snapshots = scoreSnapshotRepository.findByTargetCompanyProfileIdAndEvaluatedRoleIsNotNullOrderByCalculatedAtDesc(companyProfileId);
        }

        List<RoleScoreSnapshotResponse> responses = snapshots.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    @GetMapping("/role-evaluation-versions")
    public ResponseEntity<ApiResponse<List<RoleEvaluationVersionResponse>>> getApprovedRoleEvaluationVersions(
            @RequestParam(required = false) CompanyRole role) {

        List<RoleEvaluationVersion> versions = roleEvaluationVersionRepository.findAll(Sort.by(Sort.Direction.DESC, "approvedAt", "createdAt"));
        if (role != null) {
            versions = versions.stream()
                    .filter(version -> version.getEvaluatedRole() == role)
                    .collect(Collectors.toList());
        }

        List<RoleEvaluationVersionResponse> responses = versions.stream()
                .map(this::toVersionResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    @GetMapping("/role-score-rule-sets")
    public ResponseEntity<ApiResponse<List<RoleScoreRuleSetResponse>>> getRoleScoreRuleSets(
            @RequestParam(required = false) CompanyRole role,
            @RequestParam(required = false) Boolean active) {

        List<RoleScoreRuleSet> ruleSets = ruleSetRepository.findAll();

        if (role != null) {
            ruleSets = ruleSets.stream().filter(rs -> rs.getEvaluatedRole() == role).collect(Collectors.toList());
        }
        if (active != null) {
            ruleSets = ruleSets.stream().filter(rs -> rs.getActive().equals(active)).collect(Collectors.toList());
        }

        List<RoleScoreRuleSetResponse> responses = ruleSets.stream()
                .map(this::toRuleSetResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    private RoleScoreSnapshotResponse toResponse(ScoreSnapshot snapshot) {
        return RoleScoreSnapshotResponse.builder()
                .id(snapshot.getScoreSnapshotId())
                .targetCompanyProfileId(snapshot.getTargetCompanyProfileId())
                .targetProfileVersion(snapshot.getTargetProfileVersion())
                .referenceCompanyProfileId(snapshot.getReferenceCompanyProfileId())
                .referenceProfileVersion(snapshot.getReferenceProfileVersion())
                .evaluatedRole(snapshot.getEvaluatedRole())
                .criterionScores(jsonMapper.deserializeMap(snapshot.getCriterionScoresJson()))
                .normalizedCriterionScores(jsonMapper.deserializeMap(snapshot.getNormalizedCriterionScoresJson()))
                .weightsUsed(jsonMapper.deserializeMap(snapshot.getWeightsUsedJson()))
                .overallScore(snapshot.getOverallScore())
                .completenessStatus(snapshot.getCompletenessStatus())
                .missingCriteria(jsonMapper.deserializeList(snapshot.getMissingCriteriaJson()))
                .scoreRuleSetVersion(snapshot.getScoreRuleSetVersion())
                .weightVersion(snapshot.getWeightVersion())
                .weightingMethod(snapshot.getWeightingMethod())
                .weightSource(snapshot.getWeightSource())
                .calculatedAt(snapshot.getCalculatedAt())
                .build();
    }

    private RoleEvaluationVersionResponse toVersionResponse(RoleEvaluationVersion version) {
        Optional<CompanyProfile> profile = companyProfileRepository.findById(version.getTargetCompanyProfileId());
        String companyName = profile.map(this::companyDisplayName).orElse(version.getTargetCompanyProfileId());
        String companyId = profile.map(CompanyProfile::getCompanyId).orElse(null);
        List<String> industries = profile
                .map(CompanyProfile::getBusiness)
                .map(CompanyProfile.Business::getIndustries)
                .orElse(List.of());

        RoleEvaluationDraft sourceDraft = roleEvaluationDraftRepository.findById(version.getEvaluationId()).orElse(null);
        Map<String, RoleEvaluationVersionResponse.CriterionResponse> criteria = new LinkedHashMap<>();
        if (version.getCriteria() != null) {
            version.getCriteria().forEach((key, snapshot) -> criteria.put(key, toCriterionResponse(snapshot, sourceDraft)));
        }

        return RoleEvaluationVersionResponse.builder()
                .id(version.getId())
                .evaluationId(version.getEvaluationId())
                .projectId(version.getProjectId())
                .taskId(version.getTaskId())
                .targetCompanyProfileId(version.getTargetCompanyProfileId())
                .targetCompanyId(companyId)
                .targetCompanyName(companyName)
                .industries(industries)
                .evaluatedRole(version.getEvaluatedRole())
                .versionNumber(version.getVersionNumber())
                .status(version.getStatus())
                .completenessStatus(version.getCompletenessStatus())
                .overallScore(calculateAverageScore(version, sourceDraft))
                .criteria(criteria)
                .submittedByAccountId(version.getSubmittedByAccountId())
                .submittedAt(version.getSubmittedAt())
                .approvedByAccountId(version.getApprovedByAccountId())
                .approvedAt(version.getApprovedAt())
                .reviewComment(version.getReviewComment())
                .createdAt(version.getCreatedAt())
                .build();
    }

    private RoleEvaluationVersionResponse.CriterionResponse toCriterionResponse(CriterionSnapshot snapshot, RoleEvaluationDraft sourceDraft) {
        BigDecimal rawScore = snapshot.getRawScore();
        if (rawScore == null && sourceDraft != null && sourceDraft.getCriterionInputs() != null && snapshot.getCriterionKey() != null) {
            var input = sourceDraft.getCriterionInputs().get(snapshot.getCriterionKey());
            rawScore = input != null ? input.getRawScore() : null;
        }
        return RoleEvaluationVersionResponse.CriterionResponse.builder()
                .criterionKey(snapshot.getCriterionKey())
                .rawScore(rawScore)
                .finalRationale(snapshot.getFinalRationale())
                .inputMethod(snapshot.getInputMethod())
                .evidenceReferenceIds(snapshot.getEvidenceReferenceIds())
                .evidence(toEvidenceResponses(snapshot, sourceDraft))
                .dataSufficiencyStatus(snapshot.getDataSufficiencyStatus())
                .missingDataExplanation(snapshot.getMissingDataExplanation())
                .suggestionReviewStatus(snapshot.getSuggestionReviewStatus())
                .staffEdited(snapshot.getStaffEdited())
                .managerFeedback(snapshot.getManagerFeedback())
                .aiConfidence(snapshot.getAiConfidence())
                .build();
    }

    private List<RoleEvaluationVersionResponse.EvidenceResponse> toEvidenceResponses(CriterionSnapshot snapshot, RoleEvaluationDraft sourceDraft) {
        if (sourceDraft == null || sourceDraft.getCriterionEvidence() == null || snapshot.getCriterionKey() == null) {
            return List.of();
        }
        List<EvidenceRecord> records = sourceDraft.getCriterionEvidence().getOrDefault(snapshot.getCriterionKey(), List.of());
        return records.stream().map(this::toEvidenceResponse).collect(Collectors.toList());
    }

    private RoleEvaluationVersionResponse.EvidenceResponse toEvidenceResponse(EvidenceRecord record) {
        RawDocument rawDocument = record.getRawDocumentId() != null
                ? rawDocumentRepository.findById(record.getRawDocumentId()).orElse(null)
                : null;
        RawDocument.Source source = rawDocument != null ? rawDocument.getSource() : null;
        RawDocument.Storage storage = rawDocument != null ? rawDocument.getStorage() : null;

        return RoleEvaluationVersionResponse.EvidenceResponse.builder()
                .evidenceId(record.getEvidenceId())
                .criterionKey(record.getCriterionKey())
                .sourceType(record.getSourceType() != null ? record.getSourceType().name() : null)
                .rawDocumentId(record.getRawDocumentId())
                .fileName(source != null ? source.getFileName() : null)
                .mimeType(storage != null ? storage.getMimeType() : null)
                .sizeBytes(storage != null ? storage.getSizeBytes() : null)
                .projectId(rawDocument != null ? rawDocument.getProjectId() : null)
                .taskId(rawDocument != null ? rawDocument.getTaskId() : null)
                .evidenceCategory(record.getEvidenceCategory())
                .reliability(record.getReliability() != null ? record.getReliability().name() : null)
                .note(record.getNote())
                .externalUrl(record.getExternalUrl())
                .build();
    }

    private BigDecimal calculateAverageScore(RoleEvaluationVersion version, RoleEvaluationDraft sourceDraft) {
        if (version.getCriteria() == null || version.getCriteria().isEmpty()) {
            return null;
        }
        List<BigDecimal> scores = version.getCriteria().values().stream()
                .map(snapshot -> {
                    if (snapshot.getRawScore() != null) {
                        return snapshot.getRawScore();
                    }
                    if (sourceDraft != null && sourceDraft.getCriterionInputs() != null && snapshot.getCriterionKey() != null) {
                        var input = sourceDraft.getCriterionInputs().get(snapshot.getCriterionKey());
                        return input != null ? input.getRawScore() : null;
                    }
                    return null;
                })
                .filter(java.util.Objects::nonNull)
                .toList();
        if (scores.isEmpty()) {
            return null;
        }
        BigDecimal sum = scores.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(scores.size()), 2, RoundingMode.HALF_UP);
    }

    private String companyDisplayName(CompanyProfile profile) {
        if (profile.getIdentity() != null && profile.getIdentity().getTradeName() != null && !profile.getIdentity().getTradeName().isBlank()) {
            return profile.getIdentity().getTradeName();
        }
        if (profile.getIdentity() != null && profile.getIdentity().getLegalName() != null && !profile.getIdentity().getLegalName().isBlank()) {
            return profile.getIdentity().getLegalName();
        }
        return profile.getCompanyId() != null ? profile.getCompanyId() : profile.getId();
    }

    private RoleScoreRuleSetResponse toRuleSetResponse(RoleScoreRuleSet ruleSet) {
        return RoleScoreRuleSetResponse.builder()
                .id(ruleSet.getId())
                .evaluatedRole(ruleSet.getEvaluatedRole())
                .ruleSetVersion(ruleSet.getRuleSetVersion())
                .weightingMethod(ruleSet.getWeightingMethod())
                .weightSource(ruleSet.getWeightSource())
                .weightVersion(ruleSet.getWeightVersion())
                .active(ruleSet.getActive())
                .criteria(ruleSet.getRules().stream().map(rule -> RoleScoreRuleSetResponse.CriterionRuleResponse.builder()
                        .criterionKey(rule.getCriterionKey())
                        .criterionName(rule.getCriterionName())
                        .weight(rule.getWeight())
                        .direction(rule.getDirection())
                        .required(rule.getRequired())
                        .displayOrder(rule.getDisplayOrder())
                        .build()).collect(Collectors.toList()))
                .build();
    }
}
