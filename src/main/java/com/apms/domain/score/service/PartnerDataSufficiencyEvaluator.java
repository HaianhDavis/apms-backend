package com.apms.domain.score.service;

import com.apms.domain.score.draft.RoleEvaluationDraft;
import com.apms.domain.score.draft.RoleEvaluationVersion;
import com.apms.domain.score.enums.EvaluationCompletenessStatus;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PartnerDataSufficiencyEvaluator {

    private final PartnerEvaluationContextProvider contextProvider;

    public enum SufficiencyStatus {
        COMPLETE,
        PARTIAL,
        INCOMPLETE
    }

    @Data
    @Builder
    public static class CriterionReadiness {
        private String criterionKey;
        private SufficiencyStatus status;
        private List<String> missingCategories;
        private List<String> warnings;
    }

    // Kept for internal logic if needed, but returning RoleEvaluationReadinessResponse

    public com.apms.domain.score.dto.draft.RoleEvaluationReadinessResponse evaluate(RoleEvaluationDraft draft) {
        if (draft.getPinnedSourceReferences() == null || draft.getPinnedSourceReferences().isEmpty()) {
            return com.apms.domain.score.dto.draft.RoleEvaluationReadinessResponse.builder()
                    .evaluationId(draft.getId())
                    .aggregateCompletenessStatus(EvaluationCompletenessStatus.INCOMPLETE)
                    .staffMaySubmit(false)
                    .blockingReasons(List.of("No pinned source references"))
                    .criterionResults(new HashMap<>())
                    .build();
        }

        Map<String, com.apms.domain.score.dto.draft.CriterionReadinessResult> readinessMap = new HashMap<>();
        boolean hasInsufficient = false;
        boolean hasPartial = false;

        List<String> criteria = com.apms.domain.score.registry.CanonicalRoleCriteria.PARTNER_CRITERIA;

        for (String criterionKey : criteria) {
            CriterionReadiness readiness = evaluateCriterion(draft, criterionKey);

            com.apms.domain.score.dto.draft.CriterionReadinessResult result = com.apms.domain.score.dto.draft.CriterionReadinessResult.builder()
                    .criterionKey(readiness.getCriterionKey())
                    .sufficiencyStatus(readiness.getStatus())
                    .missingCategories(readiness.getMissingCategories())
                    .reasons(readiness.getWarnings() != null ? readiness.getWarnings() : new java.util.ArrayList<>())
                    .evidenceReferenceIds(new java.util.ArrayList<>()) // Could fetch evidence IDs here
                    .build();

            readinessMap.put(criterionKey, result);

            if (readiness.getStatus() == SufficiencyStatus.INCOMPLETE) {
                hasInsufficient = true;
            } else if (readiness.getStatus() == SufficiencyStatus.PARTIAL) {
                hasPartial = true;
            }
        }

        EvaluationCompletenessStatus aggregateStatus;
        boolean staffSubmissionAllowed;
        List<String> blockingReasons = new java.util.ArrayList<>();
        List<String> overallWarnings = new java.util.ArrayList<>();

        if (hasInsufficient) {
            aggregateStatus = EvaluationCompletenessStatus.INCOMPLETE;
            staffSubmissionAllowed = false;
            blockingReasons.add("One or more criteria have INSUFFICIENT data");
        } else if (hasPartial) {
            aggregateStatus = EvaluationCompletenessStatus.PARTIAL;
            staffSubmissionAllowed = true;
            overallWarnings.add("Evaluation is PARTIAL. Manager justification will be required later.");
        } else {
            aggregateStatus = EvaluationCompletenessStatus.COMPLETE;
            staffSubmissionAllowed = true;
        }

        return com.apms.domain.score.dto.draft.RoleEvaluationReadinessResponse.builder()
                .evaluationId(draft.getId())
                .aggregateCompletenessStatus(aggregateStatus)
                .criterionResults(readinessMap)
                .staffMaySubmit(staffSubmissionAllowed)
                .blockingReasons(blockingReasons)
                .warnings(overallWarnings)
                .evaluatedAt(java.time.LocalDateTime.now())
                .sourceSnapshotHash(draft.getSourceSnapshotHash())
                .build();
    }

    private CriterionReadiness evaluateCriterion(RoleEvaluationDraft draft, String criterionKey) {
        com.apms.domain.score.dto.draft.PartnerCriterionContext context = contextProvider.buildContext(draft, criterionKey);
        List<Map<String, Object>> sources = context.getPinnedSources();

        SufficiencyStatus status = SufficiencyStatus.INCOMPLETE;
        List<String> missingCategories = new java.util.ArrayList<>();

        boolean hasCompanyProfile = sources.stream().anyMatch(s -> "COMPANY_PROFILE_VERSION".equals(s.get("sourceType")));
        boolean hasContractClause = sources.stream().anyMatch(s -> "PARTNER_CONTRACT_CLAUSE_VERSION".equals(s.get("sourceType")));

        // Helper to check for specific metric keys
        java.util.function.Predicate<String> hasMetric = (metricKey) -> sources.stream()
                .filter(s -> "ROLE_METRIC_VERSION".equals(s.get("sourceType")))
                .anyMatch(s -> metricKey.equals(s.get("metricKey")) && s.get("actualNumericValue") != null);

        boolean hasAnyMetric = sources.stream().anyMatch(s -> "ROLE_METRIC_VERSION".equals(s.get("sourceType")));
        boolean hasAnyEvidence = sources.stream().anyMatch(s -> "ROLE_METRIC_EVIDENCE_VERSION".equals(s.get("sourceType")));

        switch (criterionKey) {
            case "businessValueContributionScore":
                if (hasMetric.test("revenue_generated") || hasMetric.test("cost_savings")) {
                    status = SufficiencyStatus.COMPLETE;
                } else if (hasAnyEvidence) {
                    status = SufficiencyStatus.PARTIAL;
                } else {
                    missingCategories.add("quantitative_metric");
                    missingCategories.add("business_value_evidence");
                }
                break;

            case "strategicAlignmentScore":
                if (hasAnyMetric || hasContractClause) { // Treat metric/clause as approved objective/initiative evidence
                    status = SufficiencyStatus.COMPLETE;
                } else if (hasCompanyProfile) {
                    status = SufficiencyStatus.PARTIAL;
                } else {
                    missingCategories.add("strategic_alignment_evidence");
                }
                break;

            case "operationalPerformanceScore":
                if (hasMetric.test("sla_uptime_percentage") || hasMetric.test("delivery_on_time_rate")) {
                    status = SufficiencyStatus.COMPLETE;
                } else if (hasAnyMetric || hasAnyEvidence) {
                    status = SufficiencyStatus.PARTIAL;
                } else {
                    missingCategories.add("operational_metric");
                }
                break;

            case "capabilityAndComplementarityScore":
                if (hasCompanyProfile && hasContractClause) {
                    status = SufficiencyStatus.COMPLETE;
                } else if (hasCompanyProfile || hasContractClause || hasAnyEvidence) {
                    status = SufficiencyStatus.PARTIAL;
                } else {
                    missingCategories.add("capability_evidence");
                    missingCategories.add("complementarity_evidence");
                }
                break;

            case "relationshipQualityScore":
                if (hasMetric.test("nps_score") || hasMetric.test("joint_initiatives_completed")) {
                    status = SufficiencyStatus.COMPLETE;
                } else if (hasAnyEvidence) {
                    status = SufficiencyStatus.PARTIAL;
                } else {
                    missingCategories.add("relationship_quality_evidence");
                }
                break;

            case "governanceAndRiskScore":
                if (hasMetric.test("compliance_audit_passed") || hasMetric.test("security_incidents") || hasContractClause) {
                    status = SufficiencyStatus.COMPLETE;
                } else if (hasAnyEvidence) {
                    status = SufficiencyStatus.PARTIAL;
                } else {
                    missingCategories.add("governance_risk_evidence");
                }
                break;
        }

        return CriterionReadiness.builder()
                .criterionKey(criterionKey)
                .status(status)
                .missingCategories(missingCategories)
                .build();
    }
}
