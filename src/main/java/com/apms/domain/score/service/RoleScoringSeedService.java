package com.apms.domain.score.service;

import com.apms.domain.company.enums.CompanyRole;
import com.apms.domain.score.RoleCriterionRule;
import com.apms.domain.score.RoleScoreRuleSet;
import com.apms.domain.score.enums.ScoreDirection;
import com.apms.domain.score.enums.WeightSource;
import com.apms.domain.score.enums.WeightingMethod;
import com.apms.domain.score.registry.CanonicalRoleCriteria;
import com.apms.domain.score.repository.sql.RoleScoreRuleSetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class RoleScoringSeedService {

    private final RoleScoreRuleSetRepository ruleSetRepository;

    @Value("${apms.scoring.seed-illustrative-rules:true}")
    private boolean seedIllustrativeRules;

    private static final String RULE_SET_VERSION = "ROLE_SCORING_V1";
    private static final String WEIGHT_VERSION = "ILLUSTRATIVE_AHP_V1";

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seedRules() {
        if (!seedIllustrativeRules) {
            return;
        }

        seedRole(CompanyRole.PARTNER, CanonicalRoleCriteria.PARTNER_CRITERIA, CanonicalRoleCriteria.PARTNER_DIRECTIONS, Map.of(
                "businessValueContributionScore", new BigDecimal("0.25"),
                "strategicAlignmentScore", new BigDecimal("0.21"),
                "operationalPerformanceScore", new BigDecimal("0.20"),
                "capabilityAndComplementarityScore", new BigDecimal("0.16"),
                "relationshipQualityScore", new BigDecimal("0.09"),
                "governanceAndRiskScore", new BigDecimal("0.09")
        ));

        // Note: For POTENTIAL_PARTNER we call seedRole, but also apply a targeted migration below
        // in case an older seed version was already deployed with incorrect weights/directions.
        seedRole(CompanyRole.POTENTIAL_PARTNER, CanonicalRoleCriteria.POTENTIAL_PARTNER_CRITERIA, CanonicalRoleCriteria.POTENTIAL_PARTNER_DIRECTIONS, Map.of(
                "strategicFitScore", new BigDecimal("0.25"),
                "capabilityComplementarityScore", new BigDecimal("0.20"),
                "trustReputationScore", new BigDecimal("0.13"),
                "financialAttractivenessScore", new BigDecimal("0.16"),
                "collaborationPotentialScore", new BigDecimal("0.16"),
                "partnershipRiskScore", new BigDecimal("0.10")
        ));
        migratePotentialPartnerIllustrativeRules();

        seedRole(CompanyRole.COMPETITOR, CanonicalRoleCriteria.COMPETITOR_CRITERIA, CanonicalRoleCriteria.COMPETITOR_DIRECTIONS, Map.of(
                "marketPositionScore", new BigDecimal("0.20"),
                "productMarketOverlapScore", new BigDecimal("0.16"),
                "competitiveCapabilityScore", new BigDecimal("0.20"),
                "strategicIntentScore", new BigDecimal("0.11"),
                "growthMomentumScore", new BigDecimal("0.09"),
                "competitiveThreatScore", new BigDecimal("0.24")
        ));

        seedRole(CompanyRole.CUSTOMER, CanonicalRoleCriteria.CUSTOMER_CRITERIA, CanonicalRoleCriteria.CUSTOMER_DIRECTIONS, Map.of(
                "revenueProfitabilityScore", new BigDecimal("0.22"),
                "purchaseBehaviorScore", new BigDecimal("0.15"),
                "customerLifetimeValueScore", new BigDecimal("0.24"),
                "retentionLoyaltyScore", new BigDecimal("0.18"),
                "growthPotentialScore", new BigDecimal("0.11"),
                "paymentChurnRiskScore", new BigDecimal("0.10")
        ));

        seedRole(CompanyRole.SUPPLIER, CanonicalRoleCriteria.SUPPLIER_CRITERIA, CanonicalRoleCriteria.SUPPLIER_DIRECTIONS, Map.of(
                "qualityPerformanceScore", new BigDecimal("0.22"),
                "costCompetitivenessScore", new BigDecimal("0.15"),
                "deliveryPerformanceScore", new BigDecimal("0.21"),
                "capacityFlexibilityScore", new BigDecimal("0.14"),
                "serviceResponsivenessScore", new BigDecimal("0.10"),
                "supplyRiskComplianceScore", new BigDecimal("0.18")
        ));
    }

    private void migratePotentialPartnerIllustrativeRules() {
        Optional<RoleScoreRuleSet> ruleSetOpt = ruleSetRepository.findByEvaluatedRoleAndRuleSetVersion(
                CompanyRole.POTENTIAL_PARTNER, RULE_SET_VERSION);
        if (ruleSetOpt.isEmpty()) {
            return;
        }
        RoleScoreRuleSet ruleSet = ruleSetOpt.get();

        // ONLY migrate if it's ILLUSTRATIVE, do NOT overwrite EXPERT created ones
        if (ruleSet.getWeightSource() != WeightSource.ILLUSTRATIVE) {
            log.info("POTENTIAL_PARTNER rule set is not ILLUSTRATIVE. Skipping migration.");
            return;
        }

        Map<String, BigDecimal> expectedWeights = Map.of(
                "strategicFitScore", new BigDecimal("0.25"),
                "capabilityComplementarityScore", new BigDecimal("0.20"),
                "trustReputationScore", new BigDecimal("0.13"),
                "financialAttractivenessScore", new BigDecimal("0.16"),
                "collaborationPotentialScore", new BigDecimal("0.16"),
                "partnershipRiskScore", new BigDecimal("0.10")
        );

        boolean updated = false;
        for (RoleCriterionRule rule : ruleSet.getRules()) {
            BigDecimal expectedWeight = expectedWeights.get(rule.getCriterionKey());
            if (expectedWeight != null && rule.getWeight().compareTo(expectedWeight) != 0) {
                rule.setWeight(expectedWeight);
                updated = true;
            }

            if ("partnershipRiskScore".equals(rule.getCriterionKey()) && rule.getDirection() == ScoreDirection.COST) {
                rule.setDirection(ScoreDirection.BENEFIT);
                updated = true;
            }
        }

        if (updated) {
            log.info("Migrated POTENTIAL_PARTNER ILLUSTRATIVE rules to new weights/directions.");
            ruleSetRepository.save(ruleSet);
        }
    }

    private void seedRole(CompanyRole role, List<String> criteriaKeys, Map<String, ScoreDirection> directions, Map<String, BigDecimal> weights) {
        if (ruleSetRepository.existsByEvaluatedRoleAndRuleSetVersion(role, RULE_SET_VERSION)) {
            // Already seeded, skip to be idempotent.
            return;
        }

        // Validate weight sums
        BigDecimal sum = weights.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.compareTo(new BigDecimal("1.00")) != 0) {
            throw new IllegalStateException("Illustrative weights for " + role + " do not sum to 1.00. Actual: " + sum);
        }

        RoleScoreRuleSet ruleSet = RoleScoreRuleSet.builder()
                .evaluatedRole(role)
                .ruleSetVersion(RULE_SET_VERSION)
                .weightingMethod(WeightingMethod.AHP)
                .weightSource(WeightSource.ILLUSTRATIVE)
                .weightVersion(WEIGHT_VERSION)
                .active(true)
                .build();

        int order = 1;
        for (String key : criteriaKeys) {
            RoleCriterionRule rule = RoleCriterionRule.builder()
                    .ruleSet(ruleSet)
                    .criterionKey(key)
                    .criterionName(formatKeyToName(key))
                    .weight(weights.get(key))
                    .direction(directions.get(key))
                    .required(true)
                    .displayOrder(order++)
                    .active(true)
                    .build();
            ruleSet.getRules().add(rule);
        }

        ruleSetRepository.save(ruleSet);
    }

    private String formatKeyToName(String key) {
        // e.g., "businessValueContributionScore" -> "Business Value Contribution Score"
        String[] parts = key.split("(?=\\p{Upper})");
        StringBuilder name = new StringBuilder();
        for (String part : parts) {
            if (name.length() > 0) name.append(" ");
            name.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return name.toString();
    }
}
