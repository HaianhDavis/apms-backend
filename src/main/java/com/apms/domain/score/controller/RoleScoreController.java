package com.apms.domain.score.controller;

import com.apms.common.response.ApiResponse;
import com.apms.domain.company.enums.CompanyRole;
import com.apms.domain.profile.service.OwnerOrganizationService;
import com.apms.domain.score.RoleScoreRuleSet;
import com.apms.domain.score.ScoreSnapshot;
import com.apms.domain.score.dto.RoleScoreRuleSetResponse;
import com.apms.domain.score.dto.RoleScoreSnapshotResponse;
import com.apms.domain.score.repository.sql.RoleScoreRuleSetRepository;
import com.apms.domain.score.repository.sql.ScoreSnapshotRepository;
import com.apms.domain.score.service.CanonicalScoreJsonMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class RoleScoreController {

    private final ScoreSnapshotRepository scoreSnapshotRepository;
    private final RoleScoreRuleSetRepository ruleSetRepository;
    private final CanonicalScoreJsonMapper jsonMapper;
    private final OwnerOrganizationService ownerOrganizationService;

    @GetMapping("/profiles/{companyProfileId}/role-scores")
    @PreAuthorize("hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DIRECTOR', 'BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF', 'KEY_MEMBER')")
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

    @GetMapping("/role-score-rule-sets")
    @PreAuthorize("hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DIRECTOR', 'BUSINESS_DEVELOPMENT_MANAGER')")
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
