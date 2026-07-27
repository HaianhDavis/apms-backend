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
 * Evaluates data sufficiency for CUSTOMER evaluations.
 * <p>
 * Missing data rules:
 * - missing values remain null, never 0 or 50
 * - INCOMPLETE blocks AI generation and submission
 * - PARTIAL permits manual score entry and requires manager justification
 * - COMPLETE permits the normal workflow
 * - never infer undisclosed revenue, profit, CLV or financial capacity
 */
@Service
@RequiredArgsConstructor
public class CustomerDataSufficiencyEvaluator {

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

        List<String> criteria = CanonicalRoleCriteria.CUSTOMER_CRITERIA;

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

        // Strict financial metrics checks
        boolean hasRevenueProfitabilityMetric = sources.stream()
                .filter(s -> s.getSourceType() == ApprovedSourceType.ROLE_METRIC_VERSION)
                .anyMatch(s -> {
                    String k = s.getCriterionKey();
                    return k != null && (k.contains("revenue") || k.contains("profit") || k.contains("margin") || k.contains("contribution") || k.contains("cost"));
                });

        boolean hasClvMetric = sources.stream()
                .filter(s -> s.getSourceType() == ApprovedSourceType.ROLE_METRIC_VERSION)
                .anyMatch(s -> {
                    String k = s.getCriterionKey();
                    return k != null && (k.contains("lifetime") || k.contains("clv") || k.contains("ltv") || k.contains("forecast") || k.contains("historical_value"));
                });

        PartnerDataSufficiencyEvaluator.SufficiencyStatus status;
        List<String> missingCategories = new ArrayList<>();

        switch (criterionKey) {
            case "revenueProfitabilityScore":
                // STRICT: revenue, gross margin, contribution and cost-to-serve evidence
                if (hasRevenueProfitabilityMetric) {
                    status = PartnerDataSufficiencyEvaluator.SufficiencyStatus.COMPLETE;
                } else if (hasCompanyProfile && hasAnyEvidence) {
                    status = PartnerDataSufficiencyEvaluator.SufficiencyStatus.PARTIAL;
                } else {
                    status = PartnerDataSufficiencyEvaluator.SufficiencyStatus.INCOMPLETE;
                    missingCategories.add("explicit_financial_evidence");
                    missingCategories.add("revenue_profitability_metrics");
                }
                break;

            case "purchaseBehaviorScore":
                // recency, frequency, monetary value, product mix and transaction history
                if (hasAnyMetric && (hasAnyEvidence || hasContractClause)) {
                    status = PartnerDataSufficiencyEvaluator.SufficiencyStatus.COMPLETE;
                } else if (hasAnyEvidence || hasCompanyProfile) {
                    status = PartnerDataSufficiencyEvaluator.SufficiencyStatus.PARTIAL;
                } else {
                    status = PartnerDataSufficiencyEvaluator.SufficiencyStatus.INCOMPLETE;
                    missingCategories.add("transaction_history_evidence");
                    missingCategories.add("purchase_behavior_data");
                }
                break;

            case "customerLifetimeValueScore":
                // STRICT: historical value and traceable forecast/model outputs only
                if (hasClvMetric) {
                    status = PartnerDataSufficiencyEvaluator.SufficiencyStatus.COMPLETE;
                } else if (hasCompanyProfile && hasAnyEvidence) {
                    status = PartnerDataSufficiencyEvaluator.SufficiencyStatus.PARTIAL;
                } else {
                    status = PartnerDataSufficiencyEvaluator.SufficiencyStatus.INCOMPLETE;
                    missingCategories.add("clv_forecast_metrics");
                    missingCategories.add("historical_value_data");
                }
                break;

            case "retentionLoyaltyScore":
                // renewals, tenure, repeated purchases, feedback/NPS and engagement
                if (hasAnyMetric && hasAnyEvidence) {
                    status = PartnerDataSufficiencyEvaluator.SufficiencyStatus.COMPLETE;
                } else if (hasAnyEvidence || hasContractClause || hasCompanyProfile) {
                    status = PartnerDataSufficiencyEvaluator.SufficiencyStatus.PARTIAL;
                } else {
                    status = PartnerDataSufficiencyEvaluator.SufficiencyStatus.INCOMPLETE;
                    missingCategories.add("retention_loyalty_evidence");
                }
                break;

            case "growthPotentialScore":
                // account expansion, cross-sell, market growth and supported pipeline
                if (hasCompanyProfile && (hasAnyMetric || hasContractClause)) {
                    status = PartnerDataSufficiencyEvaluator.SufficiencyStatus.COMPLETE;
                } else if (hasCompanyProfile || hasAnyEvidence) {
                    status = PartnerDataSufficiencyEvaluator.SufficiencyStatus.PARTIAL;
                } else {
                    status = PartnerDataSufficiencyEvaluator.SufficiencyStatus.INCOMPLETE;
                    missingCategories.add("growth_potential_evidence");
                }
                break;

            case "paymentChurnRiskScore":
                // timely payment, overdue debt, disputes, defaults and churn indicators
                if (hasAnyMetric && hasAnyEvidence) {
                    status = PartnerDataSufficiencyEvaluator.SufficiencyStatus.COMPLETE;
                } else if (hasAnyEvidence || hasContractClause) {
                    status = PartnerDataSufficiencyEvaluator.SufficiencyStatus.PARTIAL;
                } else {
                    status = PartnerDataSufficiencyEvaluator.SufficiencyStatus.INCOMPLETE;
                    missingCategories.add("payment_history_evidence");
                    missingCategories.add("churn_risk_indicators");
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
