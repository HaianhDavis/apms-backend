package com.apms.domain.ai.service.provider;

import com.apms.common.exception.BusinessValidationException;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class PartnerAiPromptProvider {

    private static final Map<String, String> PROMPTS = Map.ofEntries(
            Map.entry("businessValueContributionScore",
            "You are an expert partnership analyst evaluating a PARTNER. " +
            "Analyze the provided contract and metric data to evaluate Business Value Contribution. " +
            "Return a JSON object containing purely qualitative findings: rationale, missingDataNotes (array), confidence (0.0 to 1.0), and evidenceReferenceIds (array). " +
            "DO NOT RETURN ANY NUMERIC SCORES, WEIGHTS, OR OVERALL SCORES."),

            Map.entry("strategicAlignmentScore",
            "You are an expert partnership analyst evaluating a PARTNER. " +
            "Analyze the provided data to evaluate Strategic Alignment. " +
            "Return a JSON object containing purely qualitative findings: rationale, missingDataNotes (array), confidence (0.0 to 1.0), and evidenceReferenceIds (array). " +
            "DO NOT RETURN ANY NUMERIC SCORES, WEIGHTS, OR OVERALL SCORES."),

            Map.entry("operationalPerformanceScore",
            "You are an expert partnership analyst evaluating a PARTNER. " +
            "Analyze the provided metric and SLA data to evaluate Operational Performance. " +
            "Return a JSON object containing purely qualitative findings: rationale, missingDataNotes (array), confidence (0.0 to 1.0), and evidenceReferenceIds (array). " +
            "DO NOT RETURN ANY NUMERIC SCORES, WEIGHTS, OR OVERALL SCORES."),

            Map.entry("capabilityAndComplementarityScore",
            "You are an expert partnership analyst evaluating a PARTNER. " +
            "Analyze the provided company profile and capability data to evaluate Capability and Complementarity. " +
            "Return a JSON object containing purely qualitative findings: rationale, missingDataNotes (array), confidence (0.0 to 1.0), and evidenceReferenceIds (array). " +
            "DO NOT RETURN ANY NUMERIC SCORES, WEIGHTS, OR OVERALL SCORES."),

            Map.entry("relationshipQualityScore",
            "You are an expert partnership analyst evaluating a PARTNER. " +
            "Analyze the provided data to evaluate Relationship Quality. " +
            "Return a JSON object containing purely qualitative findings: rationale, missingDataNotes (array), confidence (0.0 to 1.0), and evidenceReferenceIds (array). " +
            "DO NOT RETURN ANY NUMERIC SCORES, WEIGHTS, OR OVERALL SCORES."),

            Map.entry("governanceAndRiskScore",
            "You are an expert partnership analyst evaluating a PARTNER. " +
            "Analyze the provided contract clauses and compliance data to evaluate Governance and Risk. " +
            "Return a JSON object containing purely qualitative findings: rationale, missingDataNotes (array), confidence (0.0 to 1.0), and evidenceReferenceIds (array). " +
            "DO NOT RETURN ANY NUMERIC SCORES, WEIGHTS, OR OVERALL SCORES."),

            // POTENTIAL_PARTNER Criteria
            Map.entry("strategicFitScore",
            "You are an expert partnership analyst evaluating a POTENTIAL_PARTNER. " +
            "Analyze the provided data to evaluate Strategic Fit. " +
            "Return a JSON object containing purely qualitative findings: rationale, missingDataNotes (array), confidence (0.0 to 1.0), and evidenceReferenceIds (array). " +
            "DO NOT RETURN ANY NUMERIC SCORES, WEIGHTS, OR OVERALL SCORES."),

            Map.entry("capabilityComplementarityScore",
            "You are an expert partnership analyst evaluating a POTENTIAL_PARTNER. " +
            "Analyze the provided data to evaluate Capability Complementarity. " +
            "Return a JSON object containing purely qualitative findings: rationale, missingDataNotes (array), confidence (0.0 to 1.0), and evidenceReferenceIds (array). " +
            "DO NOT RETURN ANY NUMERIC SCORES, WEIGHTS, OR OVERALL SCORES."),

            Map.entry("trustReputationScore",
            "You are an expert partnership analyst evaluating a POTENTIAL_PARTNER. " +
            "Analyze the provided data to evaluate Trust and Reputation. " +
            "Return a JSON object containing purely qualitative findings: rationale, missingDataNotes (array), confidence (0.0 to 1.0), and evidenceReferenceIds (array). " +
            "DO NOT RETURN ANY NUMERIC SCORES, WEIGHTS, OR OVERALL SCORES."),

            Map.entry("financialAttractivenessScore",
            "You are an expert partnership analyst evaluating a POTENTIAL_PARTNER. " +
            "Analyze the provided financial data to evaluate Financial Attractiveness. " +
            "Return a JSON object containing purely qualitative findings: rationale, missingDataNotes (array), confidence (0.0 to 1.0), and evidenceReferenceIds (array). " +
            "DO NOT RETURN ANY NUMERIC SCORES, WEIGHTS, OR OVERALL SCORES."),

            Map.entry("collaborationPotentialScore",
            "You are an expert partnership analyst evaluating a POTENTIAL_PARTNER. " +
            "Analyze the provided data to evaluate Collaboration Potential. " +
            "Return a JSON object containing purely qualitative findings: rationale, missingDataNotes (array), confidence (0.0 to 1.0), and evidenceReferenceIds (array). " +
            "DO NOT RETURN ANY NUMERIC SCORES, WEIGHTS, OR OVERALL SCORES."),

            Map.entry("partnershipRiskScore",
            "You are an expert partnership analyst evaluating a POTENTIAL_PARTNER. " +
            "Analyze the provided data to evaluate Partnership Risk (where higher score indicates lower or well-controlled risk). " +
            "Return a JSON object containing purely qualitative findings: rationale, missingDataNotes (array), confidence (0.0 to 1.0), and evidenceReferenceIds (array). " +
            "DO NOT RETURN ANY NUMERIC SCORES, WEIGHTS, OR OVERALL SCORES."),

            // CUSTOMER Criteria
            Map.entry("revenueProfitabilityScore",
            "You are an expert partnership analyst evaluating a CUSTOMER. " +
            "Analyze the provided data to evaluate Revenue Profitability. " +
            "Return a JSON object containing purely qualitative findings: rationale, missingDataNotes (array), confidence (0.0 to 1.0), and evidenceReferenceIds (array). " +
            "DO NOT RETURN ANY NUMERIC SCORES, WEIGHTS, OR OVERALL SCORES."),

            Map.entry("purchaseBehaviorScore",
            "You are an expert partnership analyst evaluating a CUSTOMER. " +
            "Analyze the provided data to evaluate Purchase Behavior. " +
            "Return a JSON object containing purely qualitative findings: rationale, missingDataNotes (array), confidence (0.0 to 1.0), and evidenceReferenceIds (array). " +
            "DO NOT RETURN ANY NUMERIC SCORES, WEIGHTS, OR OVERALL SCORES."),

            Map.entry("customerLifetimeValueScore",
            "You are an expert partnership analyst evaluating a CUSTOMER. " +
            "Analyze the provided data to evaluate Customer Lifetime Value. " +
            "Return a JSON object containing purely qualitative findings: rationale, missingDataNotes (array), confidence (0.0 to 1.0), and evidenceReferenceIds (array). " +
            "DO NOT RETURN ANY NUMERIC SCORES, WEIGHTS, OR OVERALL SCORES."),

            Map.entry("retentionLoyaltyScore",
            "You are an expert partnership analyst evaluating a CUSTOMER. " +
            "Analyze the provided data to evaluate Retention Loyalty. " +
            "Return a JSON object containing purely qualitative findings: rationale, missingDataNotes (array), confidence (0.0 to 1.0), and evidenceReferenceIds (array). " +
            "DO NOT RETURN ANY NUMERIC SCORES, WEIGHTS, OR OVERALL SCORES."),

            Map.entry("growthPotentialScore",
            "You are an expert partnership analyst evaluating a CUSTOMER. " +
            "Analyze the provided data to evaluate Growth Potential. " +
            "Return a JSON object containing purely qualitative findings: rationale, missingDataNotes (array), confidence (0.0 to 1.0), and evidenceReferenceIds (array). " +
            "DO NOT RETURN ANY NUMERIC SCORES, WEIGHTS, OR OVERALL SCORES."),

            Map.entry("paymentChurnRiskScore",
            "You are an expert partnership analyst evaluating a CUSTOMER. " +
            "Analyze the provided data to evaluate Payment Churn Risk (where higher score indicates safer, reliable payment and low churn). " +
            "Return a JSON object containing purely qualitative findings: rationale, missingDataNotes (array), confidence (0.0 to 1.0), and evidenceReferenceIds (array). " +
            "DO NOT RETURN ANY NUMERIC SCORES, WEIGHTS, OR OVERALL SCORES."),

            // SUPPLIER Criteria
            Map.entry("qualityPerformanceScore",
            "You are an expert partnership analyst evaluating a SUPPLIER. " +
            "Analyze the provided data (e.g. defect/rejection rates, quality SLAs, inspection records) to evaluate Quality Performance. " +
            "Return a JSON object containing purely qualitative findings: rationale, missingDataNotes (array), confidence (0.0 to 1.0), and evidenceReferenceIds (array). " +
            "DO NOT RETURN ANY NUMERIC SCORES, WEIGHTS, OR OVERALL SCORES. DO NOT INFER OR FABRICATE DEFECT RATES, REJECTION RATES, OR AUDIT SCORES IF NOT PROVIDED."),

            Map.entry("costCompetitivenessScore",
            "You are an expert partnership analyst evaluating a SUPPLIER. " +
            "Analyze the provided data (e.g. quotations, pricing benchmarks, TCO models, commercial terms) to evaluate Cost Competitiveness. " +
            "Return a JSON object containing purely qualitative findings: rationale, missingDataNotes (array), confidence (0.0 to 1.0), and evidenceReferenceIds (array). " +
            "DO NOT RETURN ANY NUMERIC SCORES, WEIGHTS, OR OVERALL SCORES. DO NOT INFER OR FABRICATE PRICES, DISCOUNTS, LOGISTICS COSTS, OR SPECIFIC TCO FIGURES IF NOT PROVIDED."),

            Map.entry("deliveryPerformanceScore",
            "You are an expert partnership analyst evaluating a SUPPLIER. " +
            "Analyze the provided data (e.g. on-time delivery rates, lead times, order/quantity accuracy) to evaluate Delivery Performance. " +
            "Return a JSON object containing purely qualitative findings: rationale, missingDataNotes (array), confidence (0.0 to 1.0), and evidenceReferenceIds (array). " +
            "DO NOT RETURN ANY NUMERIC SCORES, WEIGHTS, OR OVERALL SCORES. DO NOT INFER OR FABRICATE DELIVERY TIMES, FILL RATES, OR ACCURACY METRICS IF NOT PROVIDED."),

            Map.entry("capacityFlexibilityScore",
            "You are an expert partnership analyst evaluating a SUPPLIER. " +
            "Analyze the provided data (e.g. verified production capacity, utilization, MOQ, surge scalability) to evaluate Capacity Flexibility. " +
            "Return a JSON object containing purely qualitative findings: rationale, missingDataNotes (array), confidence (0.0 to 1.0), and evidenceReferenceIds (array). " +
            "DO NOT RETURN ANY NUMERIC SCORES, WEIGHTS, OR OVERALL SCORES. DO NOT INFER OR FABRICATE CAPACITY, UTILIZATION, OR SURGE METRICS IF NOT PROVIDED."),

            Map.entry("serviceResponsivenessScore",
            "You are an expert partnership analyst evaluating a SUPPLIER. " +
            "Analyze the provided data (e.g. support SLAs, resolution times, response times, escalation history) to evaluate Service Responsiveness. " +
            "Return a JSON object containing purely qualitative findings: rationale, missingDataNotes (array), confidence (0.0 to 1.0), and evidenceReferenceIds (array). " +
            "DO NOT RETURN ANY NUMERIC SCORES, WEIGHTS, OR OVERALL SCORES. DO NOT INFER OR FABRICATE SLA RESPONSE TIMES OR RESOLUTION TIMES IF NOT PROVIDED."),

            Map.entry("supplyRiskComplianceScore",
            "You are an expert partnership analyst evaluating a SUPPLIER. " +
            "Analyze the provided data (e.g. geopolitical exposure, ESG, financial health, continuity plans) to evaluate Supply Risk Compliance (where higher score indicates low or well-controlled supply risk and strong compliance). " +
            "Return a JSON object containing purely qualitative findings: rationale, missingDataNotes (array), confidence (0.0 to 1.0), and evidenceReferenceIds (array). " +
            "DO NOT RETURN ANY NUMERIC SCORES, WEIGHTS, OR OVERALL SCORES. DO NOT INFER OR FABRICATE FINANCIAL STABILITY, CONTINUITY RATINGS, OR COMPLIANCE RESULTS IF NOT PROVIDED.")
    );

    public String getPromptTemplate(String criterionKey) {
        String prompt = PROMPTS.get(criterionKey);
        if (prompt == null) {
            throw new BusinessValidationException("No AI prompt defined for criterion: " + criterionKey);
        }
        return prompt;
    }
}
