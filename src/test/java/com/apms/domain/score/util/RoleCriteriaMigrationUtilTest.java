package com.apms.domain.score.util;

import com.apms.common.exception.BusinessMigrationConflictException;
import com.apms.common.exception.BusinessValidationException;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RoleCriteriaMigrationUtilTest {

    @Test
    void shouldNormalizeLegacyKeysToCanonical() {
        Map<String, String> input = new HashMap<>();
        input.put("capabilityComplementarityScore", "val1");
        input.put("governanceComplianceScore", "val2");
        input.put("businessValueContributionScore", "val3");
        
        LinkedHashMap<String, String> result = RoleCriteriaMigrationUtil.normalizePartnerCriteriaMap(input);
        
        assertTrue(result.containsKey("capabilityAndComplementarityScore"));
        assertTrue(result.containsKey("governanceAndRiskScore"));
        assertTrue(result.containsKey("businessValueContributionScore"));
        assertEquals("val1", result.get("capabilityAndComplementarityScore"));
        assertEquals("val2", result.get("governanceAndRiskScore"));
        
        assertFalse(result.containsKey("capabilityComplementarityScore"));
        assertFalse(result.containsKey("governanceComplianceScore"));
    }

    @Test
    void shouldKeepCanonicalKeysUnchanged() {
        Map<String, String> input = new HashMap<>();
        input.put("capabilityAndComplementarityScore", "val1");
        input.put("governanceAndRiskScore", "val2");
        
        LinkedHashMap<String, String> result = RoleCriteriaMigrationUtil.normalizePartnerCriteriaMap(input);
        
        assertTrue(result.containsKey("capabilityAndComplementarityScore"));
        assertTrue(result.containsKey("governanceAndRiskScore"));
        assertEquals("val1", result.get("capabilityAndComplementarityScore"));
    }

    @Test
    void shouldThrowConflictExceptionWhenBothLegacyAndCanonicalPresent() {
        Map<String, String> input1 = new HashMap<>();
        input1.put("capabilityComplementarityScore", "val1");
        input1.put("capabilityAndComplementarityScore", "val2");
        
        assertThrows(BusinessMigrationConflictException.class, () -> 
            RoleCriteriaMigrationUtil.normalizePartnerCriteriaMap(input1)
        );

        Map<String, String> input2 = new HashMap<>();
        input2.put("governanceComplianceScore", "val1");
        input2.put("governanceAndRiskScore", "val2");
        
        assertThrows(BusinessMigrationConflictException.class, () -> 
            RoleCriteriaMigrationUtil.normalizePartnerCriteriaMap(input2)
        );
    }

    @Test
    void shouldRejectUnknownKeys() {
        Map<String, String> input = new HashMap<>();
        input.put("unknownScoreKey", "val");
        
        assertThrows(BusinessValidationException.class, () -> 
            RoleCriteriaMigrationUtil.normalizePartnerCriteriaMap(input)
        );
    }
    
    @Test
    void shouldHaveExactlySixCanonicalKeysAfterFullNormalization() {
        Map<String, String> input = new HashMap<>();
        input.put("businessValueContributionScore", "1");
        input.put("strategicAlignmentScore", "2");
        input.put("operationalPerformanceScore", "3");
        input.put("capabilityComplementarityScore", "4"); // Legacy
        input.put("relationshipQualityScore", "5");
        input.put("governanceComplianceScore", "6"); // Legacy
        
        LinkedHashMap<String, String> result = RoleCriteriaMigrationUtil.normalizePartnerCriteriaMap(input);
        
        assertEquals(6, result.size());
        assertTrue(result.containsKey("businessValueContributionScore"));
        assertTrue(result.containsKey("strategicAlignmentScore"));
        assertTrue(result.containsKey("operationalPerformanceScore"));
        assertTrue(result.containsKey("capabilityAndComplementarityScore"));
        assertTrue(result.containsKey("relationshipQualityScore"));
        assertTrue(result.containsKey("governanceAndRiskScore"));
    }
}
