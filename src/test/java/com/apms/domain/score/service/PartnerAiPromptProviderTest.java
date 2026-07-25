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
}
