package com.apms.domain.score.registry;

import com.apms.domain.company.enums.CompanyRole;
import com.apms.domain.score.enums.ScoreDirection;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class CanonicalRoleCriteria {

    private CanonicalRoleCriteria() {}

    // PARTNER
    public static final List<String> PARTNER_CRITERIA = List.of(
            "businessValueContributionScore",
            "strategicAlignmentScore",
            "operationalPerformanceScore",
            "capabilityAndComplementarityScore",
            "relationshipQualityScore",
            "governanceAndRiskScore"
    );

    public static final Map<String, ScoreDirection> PARTNER_DIRECTIONS = Map.of(
            "businessValueContributionScore", ScoreDirection.BENEFIT,
            "strategicAlignmentScore", ScoreDirection.BENEFIT,
            "operationalPerformanceScore", ScoreDirection.BENEFIT,
            "capabilityAndComplementarityScore", ScoreDirection.BENEFIT,
            "relationshipQualityScore", ScoreDirection.BENEFIT,
            "governanceAndRiskScore", ScoreDirection.BENEFIT
    );

    public static final Map<String, String> LEGACY_CRITERIA_MAPPING = Map.of(
            "capabilityComplementarityScore", "capabilityAndComplementarityScore",
            "governanceComplianceScore", "governanceAndRiskScore"
    );

    public static String normalizeCriterionKey(String key) {
        return LEGACY_CRITERIA_MAPPING.getOrDefault(key, key);
    }

    // POTENTIAL_PARTNER
    public static final List<String> POTENTIAL_PARTNER_CRITERIA = List.of(
            "strategicFitScore",
            "capabilityComplementarityScore",
            "trustReputationScore",
            "financialAttractivenessScore",
            "collaborationPotentialScore",
            "partnershipRiskScore"
    );

    public static final Map<String, ScoreDirection> POTENTIAL_PARTNER_DIRECTIONS = Map.of(
            "strategicFitScore", ScoreDirection.BENEFIT,
            "capabilityComplementarityScore", ScoreDirection.BENEFIT,
            "trustReputationScore", ScoreDirection.BENEFIT,
            "financialAttractivenessScore", ScoreDirection.BENEFIT,
            "collaborationPotentialScore", ScoreDirection.BENEFIT,
            "partnershipRiskScore", ScoreDirection.COST
    );

    // COMPETITOR
    public static final List<String> COMPETITOR_CRITERIA = List.of(
            "marketPositionScore",
            "productMarketOverlapScore",
            "competitiveCapabilityScore",
            "strategicIntentScore",
            "growthMomentumScore",
            "competitiveThreatScore"
    );

    public static final Map<String, ScoreDirection> COMPETITOR_DIRECTIONS = Map.of(
            "marketPositionScore", ScoreDirection.THREAT,
            "productMarketOverlapScore", ScoreDirection.THREAT,
            "competitiveCapabilityScore", ScoreDirection.THREAT,
            "strategicIntentScore", ScoreDirection.THREAT,
            "growthMomentumScore", ScoreDirection.THREAT,
            "competitiveThreatScore", ScoreDirection.THREAT
    );

    // CUSTOMER
    public static final List<String> CUSTOMER_CRITERIA = List.of(
            "revenueProfitabilityScore",
            "purchaseBehaviorScore",
            "customerLifetimeValueScore",
            "retentionLoyaltyScore",
            "growthPotentialScore",
            "paymentChurnRiskScore"
    );

    public static final Map<String, ScoreDirection> CUSTOMER_DIRECTIONS = Map.of(
            "revenueProfitabilityScore", ScoreDirection.BENEFIT,
            "purchaseBehaviorScore", ScoreDirection.BENEFIT,
            "customerLifetimeValueScore", ScoreDirection.BENEFIT,
            "retentionLoyaltyScore", ScoreDirection.BENEFIT,
            "growthPotentialScore", ScoreDirection.BENEFIT,
            "paymentChurnRiskScore", ScoreDirection.COST
    );

    // SUPPLIER
    public static final List<String> SUPPLIER_CRITERIA = List.of(
            "qualityPerformanceScore",
            "costCompetitivenessScore",
            "deliveryPerformanceScore",
            "capacityFlexibilityScore",
            "serviceResponsivenessScore",
            "supplyRiskComplianceScore"
    );

    public static final Map<String, ScoreDirection> SUPPLIER_DIRECTIONS = Map.of(
            "qualityPerformanceScore", ScoreDirection.BENEFIT,
            "costCompetitivenessScore", ScoreDirection.BENEFIT,
            "deliveryPerformanceScore", ScoreDirection.BENEFIT,
            "capacityFlexibilityScore", ScoreDirection.BENEFIT,
            "serviceResponsivenessScore", ScoreDirection.BENEFIT,
            "supplyRiskComplianceScore", ScoreDirection.BENEFIT
    );

    public static List<String> getCriteriaForRole(CompanyRole role) {
        return switch (role) {
            case PARTNER -> PARTNER_CRITERIA;
            case POTENTIAL_PARTNER -> POTENTIAL_PARTNER_CRITERIA;
            case COMPETITOR -> COMPETITOR_CRITERIA;
            case CUSTOMER -> CUSTOMER_CRITERIA;
            case SUPPLIER -> SUPPLIER_CRITERIA;
        };
    }

    public static ScoreDirection getDirectionForRoleAndCriterion(CompanyRole role, String criterionKey) {
        Map<String, ScoreDirection> map = switch (role) {
            case PARTNER -> PARTNER_DIRECTIONS;
            case POTENTIAL_PARTNER -> POTENTIAL_PARTNER_DIRECTIONS;
            case COMPETITOR -> COMPETITOR_DIRECTIONS;
            case CUSTOMER -> CUSTOMER_DIRECTIONS;
            case SUPPLIER -> SUPPLIER_DIRECTIONS;
        };
        return Optional.ofNullable(map.get(criterionKey))
                .orElseThrow(() -> new IllegalArgumentException("Unknown criterion for evaluated role: " + criterionKey));
    }

    public static boolean isValidCriterionForRole(CompanyRole role, String criterionKey) {
        return getCriteriaForRole(role).contains(criterionKey);
    }
}
