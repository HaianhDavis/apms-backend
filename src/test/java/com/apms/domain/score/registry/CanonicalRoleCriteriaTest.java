package com.apms.domain.score.registry;

import com.apms.domain.company.enums.CompanyRole;
import com.apms.domain.score.enums.ScoreDirection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalRoleCriteriaTest {

    @Test
    void shouldHaveFiveRolesAndSixCriteriaEach() {
        for (CompanyRole role : CompanyRole.values()) {
            List<String> criteria = CanonicalRoleCriteria.getCriteriaForRole(role);
            assertThat(criteria).hasSize(6);
        }
    }

    @Test
    void shouldHaveCorrectExactKeysAndDirectionsForPartner() {
        List<String> criteria = CanonicalRoleCriteria.getCriteriaForRole(CompanyRole.PARTNER);
        assertThat(criteria).containsExactly(
                "businessValueContributionScore",
                "strategicAlignmentScore",
                "operationalPerformanceScore",
                "capabilityAndComplementarityScore",
                "relationshipQualityScore",
                "governanceAndRiskScore"
        );

        for (String key : criteria) {
            assertThat(CanonicalRoleCriteria.getDirectionForRoleAndCriterion(CompanyRole.PARTNER, key))
                    .isEqualTo(ScoreDirection.BENEFIT);
        }
    }

    @Test
    void shouldHaveCorrectExactKeysAndDirectionsForPotentialPartner() {
        List<String> criteria = CanonicalRoleCriteria.getCriteriaForRole(CompanyRole.POTENTIAL_PARTNER);
        assertThat(criteria).containsExactly(
                "strategicFitScore",
                "capabilityComplementarityScore",
                "trustReputationScore",
                "financialAttractivenessScore",
                "collaborationPotentialScore",
                "partnershipRiskScore"
        );

        assertThat(CanonicalRoleCriteria.getDirectionForRoleAndCriterion(CompanyRole.POTENTIAL_PARTNER, "partnershipRiskScore"))
                .isEqualTo(ScoreDirection.COST);
        
        assertThat(CanonicalRoleCriteria.getDirectionForRoleAndCriterion(CompanyRole.POTENTIAL_PARTNER, "strategicFitScore"))
                .isEqualTo(ScoreDirection.BENEFIT);
    }

    @Test
    void shouldHaveCorrectExactKeysAndDirectionsForCompetitor() {
        List<String> criteria = CanonicalRoleCriteria.getCriteriaForRole(CompanyRole.COMPETITOR);
        assertThat(criteria).containsExactly(
                "marketPositionScore",
                "productMarketOverlapScore",
                "competitiveCapabilityScore",
                "strategicIntentScore",
                "growthMomentumScore",
                "competitiveThreatScore"
        );

        for (String key : criteria) {
            assertThat(CanonicalRoleCriteria.getDirectionForRoleAndCriterion(CompanyRole.COMPETITOR, key))
                    .isEqualTo(ScoreDirection.THREAT);
        }
    }

    @Test
    void shouldHaveCorrectExactKeysAndDirectionsForCustomer() {
        List<String> criteria = CanonicalRoleCriteria.getCriteriaForRole(CompanyRole.CUSTOMER);
        assertThat(criteria).containsExactly(
                "revenueProfitabilityScore",
                "purchaseBehaviorScore",
                "customerLifetimeValueScore",
                "retentionLoyaltyScore",
                "growthPotentialScore",
                "paymentChurnRiskScore"
        );

        assertThat(CanonicalRoleCriteria.getDirectionForRoleAndCriterion(CompanyRole.CUSTOMER, "paymentChurnRiskScore"))
                .isEqualTo(ScoreDirection.COST);
        
        assertThat(CanonicalRoleCriteria.getDirectionForRoleAndCriterion(CompanyRole.CUSTOMER, "revenueProfitabilityScore"))
                .isEqualTo(ScoreDirection.BENEFIT);
    }

    @Test
    void shouldHaveCorrectExactKeysAndDirectionsForSupplier() {
        List<String> criteria = CanonicalRoleCriteria.getCriteriaForRole(CompanyRole.SUPPLIER);
        assertThat(criteria).containsExactly(
                "qualityPerformanceScore",
                "costCompetitivenessScore",
                "deliveryPerformanceScore",
                "capacityFlexibilityScore",
                "serviceResponsivenessScore",
                "supplyRiskComplianceScore"
        );

        for (String key : criteria) {
            assertThat(CanonicalRoleCriteria.getDirectionForRoleAndCriterion(CompanyRole.SUPPLIER, key))
                    .isEqualTo(ScoreDirection.BENEFIT);
        }
        
        // Explicitly verify supplyRiskComplianceScore is BENEFIT
        assertThat(CanonicalRoleCriteria.getDirectionForRoleAndCriterion(CompanyRole.SUPPLIER, "supplyRiskComplianceScore"))
                .isEqualTo(ScoreDirection.BENEFIT);
    }
}
