package com.apms.domain.profile.assessment;

import com.apms.domain.contract.entity.PartnerContract;
import com.apms.domain.profile.assessment.policy.RelationshipCommercialScoringPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class RelationshipCommercialScoringPolicyTest {

    private RelationshipCommercialScoringPolicy policy;
    private final LocalDate today = LocalDate.of(2026, 9, 14);

    @BeforeEach
    void setUp() {
        policy = new RelationshipCommercialScoringPolicy();
    }

    @Test
    void testZeroContracts() {
        var res = policy.evaluate(List.of(), today, RelationshipCommercialScoringPolicy.POLICY_VERSION_V2);
        assertEquals(0, res.getApprovedContractCount());
        assertEquals(0, res.getCommercialScore());
        assertEquals(100, res.getScorableBase());
        assertEquals("NO_CONTRACTS", res.getContractValueStatus());
        assertEquals(0, res.getContractValueScore());
        assertFalse(res.isNormalizationApplied());
    }

    @Test
    void testZeroContractsV5() {
        var res = policy.evaluate(List.of(), today, RelationshipCommercialScoringPolicy.POLICY_VERSION_V5);
        assertEquals(0, res.getApprovedContractCount());
        assertNull(res.getCommercialScore());
        assertEquals(30, res.getScorableBase());
        assertEquals("SCORABLE", res.getContractValueStatus());
        assertNull(res.getContractValueScore());
        assertTrue(res.isNormalizationApplied());
        assertEquals("REFERENCE_ONLY", res.getCommercialSuggestionStatus());
        assertNull(res.getCommercialAvailablePoints());
    }

    @Test
    void testStandardVndContracts() {
        // Contract 1: 3 years ago (duration >= 24m -> 6 pts), 1.5B VND
        PartnerContract c1 = PartnerContract.builder()
                .effectiveDate(today.minusYears(3))
                .currency("VND")
                .totalContractValue(new BigDecimal("1500000000")) // 1.5B
                .build();

        // Contract 2: 2 months ago (recency <= 3m -> 6 pts), 1B VND
        PartnerContract c2 = PartnerContract.builder()
                .effectiveDate(today.minusMonths(2))
                .currency("VND")
                .totalContractValue(new BigDecimal("1000000000")) // 1B
                .build();

        // Total 2 contracts (count 2-3 -> 4 pts)
        // Total value = 2.5B VND (band 2B to 10B -> 9 pts)
        // Commercial total = 4 (count) + 6 (duration) + 6 (recency) + 9 (value) = 25 pts
        var res = policy.evaluate(List.of(c1, c2), today, RelationshipCommercialScoringPolicy.POLICY_VERSION_V2);
        assertEquals(2, res.getApprovedContractCount());
        assertEquals(4, res.getContractCountScore());
        assertEquals(6, res.getRelationshipDurationScore());
        assertEquals(6, res.getContractRecencyScore());
        assertEquals(9, res.getContractValueScore());
        assertEquals(25, res.getCommercialScore());
        assertEquals("SCORABLE", res.getContractValueStatus());
        assertEquals(100, res.getScorableBase());
        assertFalse(res.isNormalizationApplied());
    }

    @Test
    void testFutureEffectiveContract_DoesNotEarnRecencyOrDuration() {
        // Contract has effectiveDate 6 months in the future!
        PartnerContract futureContract = PartnerContract.builder()
                .effectiveDate(today.plusMonths(6))
                .signedDate(today.plusMonths(5))
                .currency("VND")
                .totalContractValue(new BigDecimal("5000000000"))
                .build();

        var res = policy.evaluate(List.of(futureContract), today, RelationshipCommercialScoringPolicy.POLICY_VERSION_V2);
        assertEquals(1, res.getApprovedContractCount());
        assertEquals(1, res.getUpcomingContractCount());
        // Duration and recency must be 0 because date is in the future in V2!
        assertEquals(0, res.getRelationshipDurationScore());
        assertEquals(0, res.getContractRecencyScore());
        assertFalse(res.isHasValidHistoricalDates());
        assertNull(res.getFirstCooperationDate());
        assertNull(res.getLatestContractDate());
    }

    @Test
    void testMissingDates_DoesNotUseCreatedAt() {
        // Contract has no signedDate and no effectiveDate, but has createdAt
        PartnerContract undatedContract = PartnerContract.builder()
                .effectiveDate(null)
                .signedDate(null)
                .createdAt(LocalDateTime.of(2023, 1, 1, 10, 0)) // old timestamp
                .currency("VND")
                .totalContractValue(new BigDecimal("100000000"))
                .build();

        var res = policy.evaluate(List.of(undatedContract), today, RelationshipCommercialScoringPolicy.POLICY_VERSION_V2);
        assertEquals(1, res.getApprovedContractCount());
        assertEquals(2, res.getContractCountScore()); // 1 contract = 2 pts
        // Must NOT use createdAt for duration/recency!
        assertEquals(0, res.getRelationshipDurationScore());
        assertEquals(0, res.getContractRecencyScore());
        assertNull(res.getFirstCooperationDate());
        assertNull(res.getLatestContractDate());
    }

    @Test
    void testNonVndContract_Sets85ScorableBaseAndUnscorableStatus() {
        PartnerContract usdContract = PartnerContract.builder()
                .effectiveDate(today.minusMonths(1))
                .currency("USD")
                .totalContractValue(new BigDecimal("100000")) // 100k USD
                .build();

        var res = policy.evaluate(List.of(usdContract), today, RelationshipCommercialScoringPolicy.POLICY_VERSION_V2);
        assertEquals(1, res.getApprovedContractCount());
        assertEquals(2, res.getContractCountScore());
        assertEquals(1, res.getRelationshipDurationScore()); // < 6m = 1 pt
        assertEquals(6, res.getContractRecencyScore()); // <= 3m = 6 pts
        assertEquals(85, res.getScorableBase());
        assertTrue(res.isNormalizationApplied());
        assertEquals("UNSCORABLE_NON_VND", res.getContractValueStatus());
        assertNull(res.getContractValueScore()); // Not scorable
        // Commercial total = 2 + 1 + 6 = 9 (out of 20 max)
        assertEquals(9, res.getCommercialScore());
    }

    @Test
    void testV3StandardVndContracts() {
        PartnerContract c1 = PartnerContract.builder()
                .effectiveDate(today.minusYears(3)) // 36 months -> 10 pts
                .currency("VND")
                .totalContractValue(new BigDecimal("1500000000")) // 1.5B
                .build();

        PartnerContract c2 = PartnerContract.builder()
                .effectiveDate(today.minusMonths(2)) // 2 months -> 10 pts
                .currency("VND")
                .totalContractValue(new BigDecimal("1000000000")) // 1B
                .build();

        var res = policy.evaluate(List.of(c1, c2), today, RelationshipCommercialScoringPolicy.POLICY_VERSION_V3);
        assertEquals(2, res.getApprovedContractCount());
        assertEquals(5, res.getContractCountScore()); // 2-3 contracts = 5 pts in V3
        assertEquals(10, res.getRelationshipDurationScore()); // >= 36m = 10 pts in V3
        assertEquals(10, res.getContractRecencyScore()); // <= 3m = 10 pts in V3
        assertEquals(12, res.getContractValueScore()); // 2.5B VND = 12 pts in V3
        assertEquals(37, res.getCommercialScore()); // 5 + 10 + 10 + 12 = 37
        assertEquals("COMPLETE", res.getCommercialSuggestionStatus());
        assertEquals(50, res.getCommercialAvailablePoints());
    }
}
