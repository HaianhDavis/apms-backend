package com.apms.domain.score.service;

import com.apms.domain.score.draft.ApprovedSourceReference;
import com.apms.domain.score.draft.RoleEvaluationDraft;
import com.apms.domain.score.dto.draft.CriterionReadinessResult;
import com.apms.domain.score.dto.draft.RoleEvaluationReadinessResponse;
import com.apms.domain.score.enums.ApprovedSourceType;
import com.apms.domain.score.enums.EvaluationCompletenessStatus;
import com.apms.domain.score.registry.CanonicalRoleCriteria;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Evaluates data sufficiency for SUPPLIER evaluations.
 * <p>
 * Missing data rules:
 * - missing values remain null, never 0 or 50
 * - INCOMPLETE blocks AI generation and submission
 * - PARTIAL permits manual score entry and requires manager justification
 * - COMPLETE permits the normal workflow
 * - never infer operational/financial metrics or audit results
 */
@Service
@RequiredArgsConstructor
public class SupplierDataSufficiencyEvaluator {

    public RoleEvaluationReadinessResponse evaluate(RoleEvaluationDraft draft) {
        if (draft.getPinnedSourceReferences() == null || draft.getPinnedSourceReferences().isEmpty()) {
            return RoleEvaluationReadinessResponse.builder()
                    .evaluationId(draft.getId())
                    .aggregateCompletenessStatus(EvaluationCompletenessStatus.INCOMPLETE)
                    .staffMaySubmit(false)
                    .blockingReasons(List.of("No pinned source references"))
                    .criterionResults(new HashMap<>())
                    .build();
        }

        Map<String, CriterionReadinessResult> readinessMap = new HashMap<>();
        boolean hasIncomplete = false;
        boolean hasPartial = false;

        List<String> criteria = CanonicalRoleCriteria.SUPPLIER_CRITERIA;

        for (String criterionKey : criteria) {
            CriterionReadinessResult result = evaluateCriterion(draft, criterionKey);
            readinessMap.put(criterionKey, result);

            if (result.getSufficiencyStatus() == PartnerDataSufficiencyEvaluator.SufficiencyStatus.INCOMPLETE) {
                hasIncomplete = true;
            } else if (result.getSufficiencyStatus() == PartnerDataSufficiencyEvaluator.SufficiencyStatus.PARTIAL) {
                hasPartial = true;
            }
        }

        EvaluationCompletenessStatus aggregateStatus;
        boolean staffSubmissionAllowed;
        List<String> blockingReasons = new ArrayList<>();
        List<String> overallWarnings = new ArrayList<>();

        if (hasIncomplete) {
            aggregateStatus = EvaluationCompletenessStatus.INCOMPLETE;
            staffSubmissionAllowed = false;
            blockingReasons.add("One or more criteria have INCOMPLETE data");
        } else if (hasPartial) {
            aggregateStatus = EvaluationCompletenessStatus.PARTIAL;
            staffSubmissionAllowed = true;
            overallWarnings.add("Evaluation is PARTIAL. Manager justification will be required on approval.");
        } else {
            aggregateStatus = EvaluationCompletenessStatus.COMPLETE;
            staffSubmissionAllowed = true;
        }

        return RoleEvaluationReadinessResponse.builder()
                .evaluationId(draft.getId())
                .aggregateCompletenessStatus(aggregateStatus)
                .criterionResults(readinessMap)
                .staffMaySubmit(staffSubmissionAllowed)
                .blockingReasons(blockingReasons)
                .warnings(overallWarnings)
                .evaluatedAt(LocalDateTime.now())
                .sourceSnapshotHash(draft.getSourceSnapshotHash())
                .build();
    }

    private CriterionReadinessResult evaluateCriterion(RoleEvaluationDraft draft, String criterionKey) {
        List<ApprovedSourceReference> sources = draft.getPinnedSourceReferences();
        if (sources == null) {
            sources = new ArrayList<>();
        }

        boolean hasCompanyProfile = sources.stream()
                .anyMatch(s -> s.getSourceType() == ApprovedSourceType.COMPANY_PROFILE_VERSION);
        boolean hasContractClause = sources.stream()
                .anyMatch(s -> s.getSourceType() == ApprovedSourceType.PARTNER_CONTRACT_CLAUSE_VERSION || s.getSourceType() == ApprovedSourceType.PARTNER_CONTRACT_VERSION);
        boolean hasAnyMetric = sources.stream()
                .anyMatch(s -> s.getSourceType() == ApprovedSourceType.ROLE_METRIC_VERSION);
        boolean hasAnyEvidence = sources.stream()
                .anyMatch(s -> s.getSourceType() == ApprovedSourceType.ROLE_METRIC_EVIDENCE_VERSION || s.getSourceType() == ApprovedSourceType.RAW_DOCUMENT_SEGMENT);

        PartnerDataSufficiencyEvaluator.SufficiencyStatus status;
        List<String> missingCategories = new ArrayList<>();

        switch (criterionKey) {
            case "qualityPerformanceScore":
                if (hasAnyMetric && (hasAnyEvidence || hasContractClause)) {
                    status = PartnerDataSufficiencyEvaluator.SufficiencyStatus.COMPLETE;
                } else if (hasAnyEvidence || hasCompanyProfile) {
                    status = PartnerDataSufficiencyEvaluator.SufficiencyStatus.PARTIAL;
                } else {
                    status = PartnerDataSufficiencyEvaluator.SufficiencyStatus.INCOMPLETE;
                    missingCategories.add("quality_performance_evidence");
                }
                break;

            case "costCompetitivenessScore":
                if (hasAnyMetric && hasContractClause) {
                    status = PartnerDataSufficiencyEvaluator.SufficiencyStatus.COMPLETE;
                } else if (hasAnyEvidence || hasCompanyProfile) {
                    status = PartnerDataSufficiencyEvaluator.SufficiencyStatus.PARTIAL;
                } else {
                    status = PartnerDataSufficiencyEvaluator.SufficiencyStatus.INCOMPLETE;
                    missingCategories.add("cost_competitiveness_data");
                }
                break;

            case "deliveryPerformanceScore":
                if (hasAnyMetric && hasAnyEvidence) {
                    status = PartnerDataSufficiencyEvaluator.SufficiencyStatus.COMPLETE;
                } else if (hasAnyEvidence || hasCompanyProfile) {
                    status = PartnerDataSufficiencyEvaluator.SufficiencyStatus.PARTIAL;
                } else {
                    status = PartnerDataSufficiencyEvaluator.SufficiencyStatus.INCOMPLETE;
                    missingCategories.add("delivery_performance_evidence");
                }
                break;

            case "capacityFlexibilityScore":
                if (hasCompanyProfile && (hasAnyMetric || hasContractClause)) {
                    status = PartnerDataSufficiencyEvaluator.SufficiencyStatus.COMPLETE;
                } else if (hasCompanyProfile || hasAnyEvidence) {
                    status = PartnerDataSufficiencyEvaluator.SufficiencyStatus.PARTIAL;
                } else {
                    status = PartnerDataSufficiencyEvaluator.SufficiencyStatus.INCOMPLETE;
                    missingCategories.add("capacity_flexibility_evidence");
                }
                break;

            case "serviceResponsivenessScore":
                if (hasAnyMetric && hasAnyEvidence) {
                    status = PartnerDataSufficiencyEvaluator.SufficiencyStatus.COMPLETE;
                } else if (hasAnyEvidence || hasContractClause) {
                    status = PartnerDataSufficiencyEvaluator.SufficiencyStatus.PARTIAL;
                } else {
                    status = PartnerDataSufficiencyEvaluator.SufficiencyStatus.INCOMPLETE;
                    missingCategories.add("service_responsiveness_evidence");
                }
                break;

            case "supplyRiskComplianceScore":
                if (hasAnyMetric && hasAnyEvidence) {
                    status = PartnerDataSufficiencyEvaluator.SufficiencyStatus.COMPLETE;
                } else if (hasAnyEvidence || hasContractClause || hasCompanyProfile) {
                    status = PartnerDataSufficiencyEvaluator.SufficiencyStatus.PARTIAL;
                } else {
                    status = PartnerDataSufficiencyEvaluator.SufficiencyStatus.INCOMPLETE;
                    missingCategories.add("supply_risk_compliance_evidence");
                }
                break;

            default:
                status = PartnerDataSufficiencyEvaluator.SufficiencyStatus.INCOMPLETE;
                missingCategories.add("unknown_criterion");
        }

        return CriterionReadinessResult.builder()
                .criterionKey(criterionKey)
                .sufficiencyStatus(status)
                .missingCategories(missingCategories)
                .reasons(new ArrayList<>())
                .evidenceReferenceIds(new ArrayList<>())
                .build();
    }
}
