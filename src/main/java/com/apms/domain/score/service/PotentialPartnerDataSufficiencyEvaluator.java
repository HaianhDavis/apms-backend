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
 * Evaluates data sufficiency for POTENTIAL_PARTNER evaluations.
 * <p>
 * Financial-data policy:
 * <ul>
 *   <li>No explicit financial evidence → INCOMPLETE, generation and submission blocked</li>
 *   <li>Limited explicit evidence → PARTIAL, manual score required, manager justification on approval</li>
 *   <li>Sufficient explicit evidence → COMPLETE</li>
 *   <li>Never infer undisclosed revenue, profit or financial capacity</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class PotentialPartnerDataSufficiencyEvaluator {

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

        List<String> criteria = CanonicalRoleCriteria.POTENTIAL_PARTNER_CRITERIA;

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
                .anyMatch(s -> s.getSourceType() == ApprovedSourceType.PARTNER_CONTRACT_CLAUSE_VERSION);
        boolean hasAnyMetric = sources.stream()
                .anyMatch(s -> s.getSourceType() == ApprovedSourceType.ROLE_METRIC_VERSION);
        boolean hasAnyEvidence = sources.stream()
                .anyMatch(s -> s.getSourceType() == ApprovedSourceType.ROLE_METRIC_EVIDENCE_VERSION);

        // Financial evidence check — explicit financial indicators only
        boolean hasExplicitFinancialEvidence = sources.stream()
                .filter(s -> s.getSourceType() == ApprovedSourceType.ROLE_METRIC_VERSION)
                .anyMatch(s -> {
                    String metricKey = s.getCriterionKey();
                    return metricKey != null && (
                            metricKey.contains("revenue") ||
                            metricKey.contains("profit") ||
                            metricKey.contains("financial") ||
                            metricKey.contains("cost_reduction") ||
                            metricKey.contains("market_access")
                    );
                });

        PartnerDataSufficiencyEvaluator.SufficiencyStatus status;
        List<String> missingCategories = new ArrayList<>();

        switch (criterionKey) {
            case "strategicFitScore":
                // Sources: objectives, industry, markets, products/services, direction
                if (hasCompanyProfile && (hasAnyMetric || hasContractClause)) {
                    status = PartnerDataSufficiencyEvaluator.SufficiencyStatus.COMPLETE;
                } else if (hasCompanyProfile) {
                    status = PartnerDataSufficiencyEvaluator.SufficiencyStatus.PARTIAL;
                } else {
                    status = PartnerDataSufficiencyEvaluator.SufficiencyStatus.INCOMPLETE;
                    missingCategories.add("company_profile_data");
                    missingCategories.add("strategic_direction_evidence");
                }
                break;

            case "capabilityComplementarityScore":
                // Sources: technology, expertise, people, infrastructure, networks
                if (hasCompanyProfile && hasAnyEvidence) {
                    status = PartnerDataSufficiencyEvaluator.SufficiencyStatus.COMPLETE;
                } else if (hasCompanyProfile || hasAnyEvidence) {
                    status = PartnerDataSufficiencyEvaluator.SufficiencyStatus.PARTIAL;
                } else {
                    status = PartnerDataSufficiencyEvaluator.SufficiencyStatus.INCOMPLETE;
                    missingCategories.add("capability_evidence");
                    missingCategories.add("complementarity_evidence");
                }
                break;

            case "trustReputationScore":
                // Sources: history, feedback, transparency, disputes, commitments
                if (hasAnyMetric && hasAnyEvidence) {
                    status = PartnerDataSufficiencyEvaluator.SufficiencyStatus.COMPLETE;
                } else if (hasAnyEvidence || hasCompanyProfile) {
                    status = PartnerDataSufficiencyEvaluator.SufficiencyStatus.PARTIAL;
                } else {
                    status = PartnerDataSufficiencyEvaluator.SufficiencyStatus.INCOMPLETE;
                    missingCategories.add("trust_reputation_evidence");
                }
                break;

            case "financialAttractivenessScore":
                // Sources: potential revenue, market access, cost reduction
                // STRICT: never infer undisclosed financial data
                if (hasExplicitFinancialEvidence) {
                    status = PartnerDataSufficiencyEvaluator.SufficiencyStatus.COMPLETE;
                } else if (hasCompanyProfile && hasAnyEvidence) {
                    // Profile exists but no explicit financial metrics — PARTIAL, manual required
                    status = PartnerDataSufficiencyEvaluator.SufficiencyStatus.PARTIAL;
                } else {
                    status = PartnerDataSufficiencyEvaluator.SufficiencyStatus.INCOMPLETE;
                    missingCategories.add("explicit_financial_evidence");
                    missingCategories.add("revenue_or_cost_metrics");
                }
                break;

            case "collaborationPotentialScore":
                // Sources: joint products, resource sharing, cross-selling, new customer groups
                if (hasContractClause && hasAnyEvidence) {
                    status = PartnerDataSufficiencyEvaluator.SufficiencyStatus.COMPLETE;
                } else if (hasContractClause || hasAnyEvidence || hasCompanyProfile) {
                    status = PartnerDataSufficiencyEvaluator.SufficiencyStatus.PARTIAL;
                } else {
                    status = PartnerDataSufficiencyEvaluator.SufficiencyStatus.INCOMPLETE;
                    missingCategories.add("collaboration_evidence");
                }
                break;

            case "partnershipRiskScore":
                // Sources: financial uncertainty, partnership experience, legal/security concerns
                // Risk safety — higher = lower or well-controlled partnership risk
                if (hasAnyMetric && (hasContractClause || hasAnyEvidence)) {
                    status = PartnerDataSufficiencyEvaluator.SufficiencyStatus.COMPLETE;
                } else if (hasAnyEvidence || hasContractClause) {
                    status = PartnerDataSufficiencyEvaluator.SufficiencyStatus.PARTIAL;
                } else {
                    status = PartnerDataSufficiencyEvaluator.SufficiencyStatus.INCOMPLETE;
                    missingCategories.add("risk_assessment_evidence");
                    missingCategories.add("legal_security_evidence");
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
