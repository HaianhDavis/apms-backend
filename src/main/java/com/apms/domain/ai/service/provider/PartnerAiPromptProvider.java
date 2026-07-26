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
            "DO NOT RETURN ANY NUMERIC SCORES, WEIGHTS, OR OVERALL SCORES.")
    );

    public String getPromptTemplate(String criterionKey) {
        String prompt = PROMPTS.get(criterionKey);
        if (prompt == null) {
            throw new BusinessValidationException("No AI prompt defined for criterion: " + criterionKey);
        }
        return prompt;
    }
}
