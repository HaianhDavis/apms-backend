package com.apms.domain.score.service;

import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.ai.service.provider.PartnerAiPromptProvider;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PartnerAiPromptProviderTest {

    private final PartnerAiPromptProvider provider = new PartnerAiPromptProvider();

    @Test
    void testValidCriteriaReturnsPrompt() {
        String prompt = provider.getPromptTemplate("businessValueContributionScore");
        assertNotNull(prompt);
        assertTrue(prompt.contains("Business Value Contribution"));
        assertTrue(prompt.contains("DO NOT RETURN ANY NUMERIC SCORES"));
    }

    @Test
    void testInvalidCriterionThrowsException() {
        assertThrows(BusinessValidationException.class, () -> {
            provider.getPromptTemplate("invalidScore");
        });
    }

    @Test
    void testAllSixCriteriaHavePrompts() {
        String[] criteria = {
                "businessValueContributionScore",
                "strategicAlignmentScore",
                "operationalPerformanceScore",
                "capabilityAndComplementarityScore",
                "relationshipQualityScore",
                "governanceAndRiskScore"
        };
        for (String c : criteria) {
            String p = provider.getPromptTemplate(c);
            assertNotNull(p);
            assertTrue(p.contains("DO NOT RETURN ANY NUMERIC SCORES"));
        }
    }

    // CUSTOMER prompt-provider tests covering all six keys

    @Test
    void testAllSixCustomerCriteriaHavePrompts() {
        String[] customerCriteria = {
                "revenueProfitabilityScore",
                "purchaseBehaviorScore",
                "customerLifetimeValueScore",
                "retentionLoyaltyScore",
                "growthPotentialScore",
                "paymentChurnRiskScore"
        };
        for (String c : customerCriteria) {
            String p = provider.getPromptTemplate(c);
            assertNotNull(p, "Missing prompt for " + c);
            // Qualitative findings and rationale are requested
            assertTrue(p.contains("rationale"), c + " must request rationale");
            assertTrue(p.contains("qualitative findings"), c + " must request qualitative findings");
            // Evidence references are retained
            assertTrue(p.contains("evidenceReferenceIds"), c + " must retain evidence references");
            // No official numeric score
            assertTrue(p.contains("DO NOT RETURN ANY NUMERIC SCORES"), c + " must prohibit numeric scores");
            // No weight
            assertFalse(p.contains("weight:"), c + " must not contain weight:");
            // No overallScore
            assertFalse(p.contains("overallScore"), c + " must not contain overallScore");
            // No invented revenue, profit or CLV
            assertFalse(p.contains("$"), c + " must not contain dollar amounts");
            assertFalse(p.contains("revenue:"), c + " must not invent revenue figures");
        }
    }

    @Test
    void testRevenueProfitabilityScorePrompt() {
        String p = provider.getPromptTemplate("revenueProfitabilityScore");
        assertTrue(p.contains("CUSTOMER"));
        assertTrue(p.contains("Revenue Profitability"));
    }

    @Test
    void testPurchaseBehaviorScorePrompt() {
        String p = provider.getPromptTemplate("purchaseBehaviorScore");
        assertTrue(p.contains("CUSTOMER"));
        assertTrue(p.contains("Purchase Behavior"));
    }

    @Test
    void testCustomerLifetimeValueScorePrompt() {
        String p = provider.getPromptTemplate("customerLifetimeValueScore");
        assertTrue(p.contains("CUSTOMER"));
        assertTrue(p.contains("Customer Lifetime Value"));
    }

    @Test
    void testRetentionLoyaltyScorePrompt() {
        String p = provider.getPromptTemplate("retentionLoyaltyScore");
        assertTrue(p.contains("CUSTOMER"));
        assertTrue(p.contains("Retention Loyalty"));
    }

    @Test
    void testGrowthPotentialScorePrompt() {
        String p = provider.getPromptTemplate("growthPotentialScore");
        assertTrue(p.contains("CUSTOMER"));
        assertTrue(p.contains("Growth Potential"));
    }

    @Test
    void testPaymentChurnRiskScorePrompt() {
        String p = provider.getPromptTemplate("paymentChurnRiskScore");
        assertTrue(p.contains("CUSTOMER"));
        assertTrue(p.contains("Payment Churn Risk"));
        assertTrue(p.contains("higher score indicates safer"), "Must define BENEFIT direction semantics");
    }

    // SUPPLIER prompt-provider tests covering all six keys

    @Test
    void testAllSixSupplierCriteriaHavePrompts() {
        String[] supplierCriteria = {
                "qualityPerformanceScore",
                "costCompetitivenessScore",
                "deliveryPerformanceScore",
                "capacityFlexibilityScore",
                "serviceResponsivenessScore",
                "supplyRiskComplianceScore"
        };
        for (String c : supplierCriteria) {
            String p = provider.getPromptTemplate(c);
            assertNotNull(p, "Missing prompt for " + c);
            assertTrue(p.contains("rationale"), c + " must request rationale");
            assertTrue(p.contains("qualitative findings"), c + " must request qualitative findings");
            assertTrue(p.contains("evidenceReferenceIds"), c + " must retain evidence references");
            assertTrue(p.contains("DO NOT RETURN ANY NUMERIC SCORES"), c + " must prohibit numeric scores");
            assertFalse(p.contains("weight:"), c + " must not contain weight:");
            assertFalse(p.contains("overallScore"), c + " must not contain overallScore");
            assertTrue(p.contains("DO NOT INFER OR FABRICATE"), c + " must prohibit inference");
        }
    }

    @Test
    void testQualityPerformanceScorePrompt() {
        String p = provider.getPromptTemplate("qualityPerformanceScore");
        assertTrue(p.contains("SUPPLIER"));
        assertTrue(p.contains("Quality Performance"));
        assertTrue(p.contains("defect/rejection rates"));
    }

    @Test
    void testCostCompetitivenessScorePrompt() {
        String p = provider.getPromptTemplate("costCompetitivenessScore");
        assertTrue(p.contains("SUPPLIER"));
        assertTrue(p.contains("Cost Competitiveness"));
        assertTrue(p.contains("PRICES"));
    }

    @Test
    void testDeliveryPerformanceScorePrompt() {
        String p = provider.getPromptTemplate("deliveryPerformanceScore");
        assertTrue(p.contains("SUPPLIER"));
        assertTrue(p.contains("Delivery Performance"));
        assertTrue(p.contains("DELIVERY TIMES"));
    }

    @Test
    void testCapacityFlexibilityScorePrompt() {
        String p = provider.getPromptTemplate("capacityFlexibilityScore");
        assertTrue(p.contains("SUPPLIER"));
        assertTrue(p.contains("Capacity Flexibility"));
        assertTrue(p.contains("capacity"));
    }

    @Test
    void testServiceResponsivenessScorePrompt() {
        String p = provider.getPromptTemplate("serviceResponsivenessScore");
        assertTrue(p.contains("SUPPLIER"));
        assertTrue(p.contains("Service Responsiveness"));
        assertTrue(p.contains("response times"));
    }

    @Test
    void testSupplyRiskComplianceScorePrompt() {
        String p = provider.getPromptTemplate("supplyRiskComplianceScore");
        assertTrue(p.contains("SUPPLIER"));
        assertTrue(p.contains("Supply Risk Compliance"));
        assertTrue(p.contains("higher score indicates low or well-controlled supply risk"));
    }
}
