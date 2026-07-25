package com.apms.domain.ai.service.provider;

import com.apms.common.exception.BusinessValidationException;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class PartnerAiPromptProvider {

    private static final Map<String, String> PROMPTS = Map.of(
            "businessValueContributionScore",
            "You are an expert partnership analyst evaluating a PARTNER. " +
            "Analyze the provided contract and metric data to evaluate Business Value Contribution. " +
            "Return a JSON object containing purely qualitative findings: rationale, missingDataNotes (array), confidence (0.0 to 1.0), and evidenceReferenceIds (array). " +
            "DO NOT RETURN ANY NUMERIC SCORES, WEIGHTS, OR OVERALL SCORES.",

            "strategicAlignmentScore",
            "You are an expert partnership analyst evaluating a PARTNER. " +
            "Analyze the provided data to evaluate Strategic Alignment. " +
            "Return a JSON object containing purely qualitative findings: rationale, missingDataNotes (array), confidence (0.0 to 1.0), and evidenceReferenceIds (array). " +
            "DO NOT RETURN ANY NUMERIC SCORES, WEIGHTS, OR OVERALL SCORES.",

            "operationalPerformanceScore",
            "You are an expert partnership analyst evaluating a PARTNER. " +
            "Analyze the provided metric and SLA data to evaluate Operational Performance. " +
            "Return a JSON object containing purely qualitative findings: rationale, missingDataNotes (array), confidence (0.0 to 1.0), and evidenceReferenceIds (array). " +
            "DO NOT RETURN ANY NUMERIC SCORES, WEIGHTS, OR OVERALL SCORES.",

            "capabilityAndComplementarityScore",
            "You are an expert partnership analyst evaluating a PARTNER. " +
            "Analyze the provided company profile and capability data to evaluate Capability and Complementarity. " +
            "Return a JSON object containing purely qualitative findings: rationale, missingDataNotes (array), confidence (0.0 to 1.0), and evidenceReferenceIds (array). " +
            "DO NOT RETURN ANY NUMERIC SCORES, WEIGHTS, OR OVERALL SCORES.",

            "relationshipQualityScore",
            "You are an expert partnership analyst evaluating a PARTNER. " +
            "Analyze the provided data to evaluate Relationship Quality. " +
            "Return a JSON object containing purely qualitative findings: rationale, missingDataNotes (array), confidence (0.0 to 1.0), and evidenceReferenceIds (array). " +
            "DO NOT RETURN ANY NUMERIC SCORES, WEIGHTS, OR OVERALL SCORES.",

            "governanceAndRiskScore",
            "You are an expert partnership analyst evaluating a PARTNER. " +
            "Analyze the provided contract clauses and compliance data to evaluate Governance and Risk. " +
            "Return a JSON object containing purely qualitative findings: rationale, missingDataNotes (array), confidence (0.0 to 1.0), and evidenceReferenceIds (array). " +
            "DO NOT RETURN ANY NUMERIC SCORES, WEIGHTS, OR OVERALL SCORES."
    );

    public String getPromptTemplate(String criterionKey) {
        String prompt = PROMPTS.get(criterionKey);
        if (prompt == null) {
            throw new BusinessValidationException("No AI prompt defined for criterion: " + criterionKey);
        }
        return prompt;
    }
}
