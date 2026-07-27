package com.apms.domain.score.service;

import com.apms.domain.profile.repository.mongo.CompanyProfileVersionRepository;
import com.apms.domain.profile.service.OwnerOrganizationService;
import com.apms.domain.score.RoleScoreRuleSet;
import com.apms.domain.score.ScoreSnapshot;
import com.apms.domain.score.dto.RoleEvaluationCalculationRequest;
import com.apms.domain.score.dto.RoleEvaluationCalculationResult;
import com.apms.domain.score.engine.RoleScoringEngine;
import com.apms.domain.score.repository.sql.RoleScoreRuleSetRepository;
import com.apms.domain.score.repository.sql.ScoreSnapshotRepository;
import com.apms.domain.user.Account;
import com.apms.domain.user.repository.sql.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Creates immutable canonical role-scoring snapshots.
 * <p>
 * Canonical snapshots live side-by-side with legacy snapshots in the same
 * {@code score_snapshots} table. They are distinguished by having a non-null
 * {@code evaluatedRole}. All legacy score fields (partnerFitScore,
 * competitionLevel, etc.) are left null on canonical rows.
 * <p>
 * This service is not exposed via a public endpoint in Phase 1.
 * Canonical scoring is not triggered automatically by CandidateApprovedEvent.
 */
@Service
@RequiredArgsConstructor
public class CanonicalScoreSnapshotService {

    private final RoleScoringEngine scoringEngine;
    private final ScoreSnapshotRepository scoreSnapshotRepository;
    private final RoleScoreRuleSetRepository ruleSetRepository;
    private final OwnerOrganizationService ownerOrganizationService;
    private final CompanyProfileVersionRepository versionRepository;
    private final CanonicalScoreJsonMapper jsonMapper;
    private final AccountRepository accountRepository;

    @Transactional
    public ScoreSnapshot createCanonicalSnapshot(RoleEvaluationCalculationRequest request) {
        // 1. Validate target
        if (request.getTargetCompanyProfileId() == null || request.getTargetCompanyProfileId().isBlank()) {
            throw new IllegalArgumentException("Target company profile ID must be provided.");
        }

        String ownerId = ownerOrganizationService.getOwnerCompanyId();
        if (request.getTargetCompanyProfileId().equals(ownerId)) {
            throw new IllegalArgumentException("The Owner Organization cannot be evaluated as a target company.");
        }

        // 2. Validate target profile version exists
        if (request.getTargetProfileVersion() != null) {
            versionRepository.findByCompanyProfileIdAndVersion(
                    request.getTargetCompanyProfileId(), request.getTargetProfileVersion())
                    .orElseThrow(() -> new IllegalArgumentException("Target CompanyProfile version snapshot was not found."));
        }

        // 3. Validate reference equals configured owner (if required)
        if (request.getEvaluatedRole() != com.apms.domain.company.enums.CompanyRole.POTENTIAL_PARTNER) {
            if (!ownerId.equals(request.getReferenceCompanyProfileId())) {
                throw new IllegalArgumentException("Reference company must be the configured Owner Organization.");
            }

            // 4. Validate reference profile version exists
            if (request.getReferenceProfileVersion() != null) {
                versionRepository.findByCompanyProfileIdAndVersion(
                        request.getReferenceCompanyProfileId(), request.getReferenceProfileVersion())
                        .orElseThrow(() -> new IllegalArgumentException("Reference CompanyProfile version snapshot was not found."));
            }
        }

        // 5. Calculate
        RoleEvaluationCalculationResult result = scoringEngine.calculate(request);

        // 6. Resolve rule set for FK link
        RoleScoreRuleSet ruleSet = ruleSetRepository.findByEvaluatedRoleAndRuleSetVersion(
                        request.getEvaluatedRole(), request.getRuleSetVersion())
                .orElseThrow(() -> new IllegalArgumentException("Active rule set not found for role: " + request.getEvaluatedRole()));

        // 7. Resolve account if provided
        Account calculatedBy = null;
        if (request.getCalculatedByAccountId() != null) {
            calculatedBy = accountRepository.findById(request.getCalculatedByAccountId()).orElse(null);
        }

        // 8. Build immutable canonical snapshot — legacy fields are null
        ScoreSnapshot snapshot = new ScoreSnapshot();
        snapshot.setTargetCompanyProfileId(request.getTargetCompanyProfileId());
        snapshot.setTargetProfileVersion(request.getTargetProfileVersion());
        snapshot.setReferenceCompanyProfileId(request.getReferenceCompanyProfileId());
        snapshot.setReferenceProfileVersion(request.getReferenceProfileVersion());
        snapshot.setEvaluatedRole(result.getEvaluatedRole());
        snapshot.setRoleScoreRuleSet(ruleSet);
        snapshot.setScoreRuleSetVersion(result.getRuleSetVersion());
        snapshot.setWeightingMethod(result.getWeightingMethod());
        snapshot.setWeightSource(result.getWeightSource());
        snapshot.setWeightVersion(result.getWeightVersion());
        snapshot.setCriterionScoresJson(jsonMapper.serializeMap(result.getCriterionScores()));
        snapshot.setNormalizedCriterionScoresJson(jsonMapper.serializeMap(result.getNormalizedCriterionScores()));
        snapshot.setWeightsUsedJson(jsonMapper.serializeMap(result.getWeightsUsed()));
        snapshot.setOverallScore(result.getOverallScore());
        snapshot.setCompletenessStatus(result.getCompletenessStatus());
        snapshot.setMissingCriteriaJson(jsonMapper.serializeList(result.getMissingCriteria()));
        snapshot.setEvidenceRefsJson(jsonMapper.serializeEvidenceMap(request.getCriterionEvidenceRefs()));
        snapshot.setCalculatedByAccount(calculatedBy);
        snapshot.setCalculatedAt(Instant.now());
        snapshot.setSourceEvaluationDraftId(request.getSourceEvaluationDraftId());
        snapshot.setApprovalIdempotencyKey(request.getApprovalIdempotencyKey());
        snapshot.setApprovedRoleEvaluationVersionId(request.getApprovedRoleEvaluationVersionId());
        snapshot.setApprovedRoleEvaluationVersionNumber(request.getApprovedRoleEvaluationVersionNumber());

        // Legacy structural fields (companyId, project, candidateId, ruleVersion) are left null.
        // Legacy score fields (partnerFitScore, competitionLevel, etc.) are left null.
        // This is safe because those columns were made nullable in the schema migration.

        return scoreSnapshotRepository.save(snapshot);
    }
}
