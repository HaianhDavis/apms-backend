package com.apms.domain.profile.assessment;

import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.profile.assessment.policy.RelationshipScoreCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class RelationshipScoreCalculatorTest {

    private RelationshipScoreCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new RelationshipScoreCalculator();
    }

    @Test
    void testPerfectScore_RankA() {
        // Commercial: 35, Cooperation: 20, Strategic: 20, Network: 15, Engagement: 5, Qualitative: 5 = 100
        var result = calculator.calculate(35, 20, 20, 15, 5, 5, 100, false);
        assertEquals(100, result.getRawScorableScore());
        assertEquals(100, result.getNormalizedTotalScore());
        assertEquals(RelationshipAssessmentRank.A, result.getRank());
        assertFalse(result.isNormalizationApplied());
    }

    @Test
    void testRankBoundaries() {
        // 90 -> A
        var res90 = calculator.calculate(25, 20, 20, 15, 5, 5, 100, false);
        assertEquals(90, res90.getNormalizedTotalScore());
        assertEquals(RelationshipAssessmentRank.A, res90.getRank());

        // 89 -> B
        var res89 = calculator.calculate(24, 20, 20, 15, 5, 5, 100, false);
        assertEquals(89, res89.getNormalizedTotalScore());
        assertEquals(RelationshipAssessmentRank.B, res89.getRank());

        // 60 -> B
        var res60 = calculator.calculate(15, 15, 15, 9, 3, 3, 100, false);
        assertEquals(60, res60.getNormalizedTotalScore());
        assertEquals(RelationshipAssessmentRank.B, res60.getRank());

        // 59 -> C
        var res59 = calculator.calculate(14, 15, 15, 9, 3, 3, 100, false);
        assertEquals(59, res59.getNormalizedTotalScore());
        assertEquals(RelationshipAssessmentRank.C, res59.getRank());

        // 30 -> C
        var res30 = calculator.calculate(10, 5, 5, 6, 2, 2, 100, false);
        assertEquals(30, res30.getNormalizedTotalScore());
        assertEquals(RelationshipAssessmentRank.C, res30.getRank());

        // 29 -> D
        var res29 = calculator.calculate(9, 5, 5, 6, 2, 2, 100, false);
        assertEquals(29, res29.getNormalizedTotalScore());
        assertEquals(RelationshipAssessmentRank.D, res29.getRank());

        // 0 -> D
        var res0 = calculator.calculate(0, 0, 0, 0, 0, 0, 100, false);
        assertEquals(0, res0.getNormalizedTotalScore());
        assertEquals(RelationshipAssessmentRank.D, res0.getRank());
    }

    @Test
    void testInvalidRubrics_Rejected() {
        // Cooperation 17 invalid
        assertThrows(BusinessValidationException.class, () ->
                calculator.calculate(10, 17, 20, 15, 5, 5, 100, false));

        // Strategic 7 invalid
        assertThrows(BusinessValidationException.class, () ->
                calculator.calculate(10, 20, 7, 15, 5, 5, 100, false));

        // Network 14 invalid
        assertThrows(BusinessValidationException.class, () ->
                calculator.calculate(10, 20, 20, 14, 5, 5, 100, false));

        // Engagement 6 invalid
        assertThrows(BusinessValidationException.class, () ->
                calculator.calculate(10, 20, 20, 15, 6, 5, 100, false));

        // Qualitative -1 invalid
        assertThrows(BusinessValidationException.class, () ->
                calculator.calculate(10, 20, 20, 15, 5, -1, 100, false));
    }

    @Test
    void testNonVndNormalization_85PointBase() {
        // Commercial count/duration/recency = 18 (out of 20)
        // Cooperation: 15, Strategic: 15, Network: 12, Engagement: 4, Qualitative: 4 (human sum = 50)
        // Raw = 18 + 50 = 68 / 85
        // Normalized = round(68 / 85 * 100) = round(80.0) = 80 -> Rank B
        var result = calculator.calculate(18, 15, 15, 12, 4, 4, 85, true);
        assertEquals(68, result.getRawScorableScore());
        assertEquals(80, result.getNormalizedTotalScore());
        assertEquals(85, result.getScorableBase());
        assertTrue(result.isNormalizationApplied());
        assertEquals(RelationshipAssessmentRank.B, result.getRank());

        // Example from prompt: 72 / 85 * 100 = 84.705... -> round = 85 -> Rank B
        var res72 = calculator.calculate(18, 15, 15, 15, 5, 4, 85, true);
        assertEquals(72, res72.getRawScorableScore());
        assertEquals(85, res72.getNormalizedTotalScore());
        assertEquals(RelationshipAssessmentRank.B, res72.getRank());
    }

    @Test
    void testV2ManualCalculation_DirectSumNo85PtNormalization() {
        // Commercial: 24, Cooperation: 18, Strategic: 13, Network: 7, Engagement: 4, Qualitative: 4
        // Total = 24 + 18 + 13 + 7 + 4 + 4 = 70 -> Rank B
        var result = calculator.calculateV2(24, 18, 13, 7, 4, 4);
        assertEquals(70, result.getRawScorableScore());
        assertEquals(70, result.getNormalizedTotalScore());
        assertEquals(100, result.getScorableBase());
        assertFalse(result.isNormalizationApplied());
        assertEquals(RelationshipAssessmentRank.B, result.getRank());
    }

    @Test
    void testV2RankBoundaries() {
        // Perfect 100 -> A
        var res100 = calculator.calculateV2(35, 25, 20, 10, 5, 5);
        assertEquals(100, res100.getNormalizedTotalScore());
        assertEquals(RelationshipAssessmentRank.A, res100.getRank());

        // 90 -> A
        var res90 = calculator.calculateV2(30, 25, 20, 8, 4, 3);
        assertEquals(90, res90.getNormalizedTotalScore());
        assertEquals(RelationshipAssessmentRank.A, res90.getRank());

        // 89 -> B
        var res89 = calculator.calculateV2(30, 24, 20, 8, 4, 3);
        assertEquals(89, res89.getNormalizedTotalScore());
        assertEquals(RelationshipAssessmentRank.B, res89.getRank());

        // 60 -> B
        var res60 = calculator.calculateV2(20, 15, 12, 7, 3, 3);
        assertEquals(60, res60.getNormalizedTotalScore());
        assertEquals(RelationshipAssessmentRank.B, res60.getRank());

        // 59 -> C
        var res59 = calculator.calculateV2(20, 15, 12, 6, 3, 3);
        assertEquals(59, res59.getNormalizedTotalScore());
        assertEquals(RelationshipAssessmentRank.C, res59.getRank());

        // 30 -> C
        var res30 = calculator.calculateV2(10, 10, 5, 3, 1, 1);
        assertEquals(30, res30.getNormalizedTotalScore());
        assertEquals(RelationshipAssessmentRank.C, res30.getRank());

        // 29 -> D
        var res29 = calculator.calculateV2(10, 10, 5, 2, 1, 1);
        assertEquals(29, res29.getNormalizedTotalScore());
        assertEquals(RelationshipAssessmentRank.D, res29.getRank());

        // 0 -> D
        var res0 = calculator.calculateV2(0, 0, 0, 0, 0, 0);
        assertEquals(0, res0.getNormalizedTotalScore());
        assertEquals(RelationshipAssessmentRank.D, res0.getRank());
    }

    @Test
    void testV2DraftRangeValidation_AllowsNullsAndChecksBounds() {
        // Nulls are allowed in draft
        assertDoesNotThrow(() -> calculator.validateDraftRangesV2(null, null, null, null, null, null));
        assertDoesNotThrow(() -> calculator.validateDraftRangesV2(25, null, 15, null, 3, null));

        // Upper bound violations
        assertThrows(BusinessValidationException.class, () ->
                calculator.validateDraftRangesV2(36, 20, 15, 8, 4, 4));
        assertThrows(BusinessValidationException.class, () ->
                calculator.validateDraftRangesV2(30, 26, 15, 8, 4, 4));
        assertThrows(BusinessValidationException.class, () ->
                calculator.validateDraftRangesV2(30, 20, 21, 8, 4, 4));
        assertThrows(BusinessValidationException.class, () ->
                calculator.validateDraftRangesV2(30, 20, 15, 11, 4, 4));
        assertThrows(BusinessValidationException.class, () ->
                calculator.validateDraftRangesV2(30, 20, 15, 8, 6, 4));
        assertThrows(BusinessValidationException.class, () ->
                calculator.validateDraftRangesV2(30, 20, 15, 8, 4, 6));

        // Negative values
        assertThrows(BusinessValidationException.class, () ->
                calculator.validateDraftRangesV2(-1, 20, 15, 8, 4, 4));
    }

    @Test
    void testV2SubmissionValidation_RejectsMissingOrInvalidScores() {
        // Missing any criterion throws exception
        assertThrows(BusinessValidationException.class, () ->
                calculator.validateCompleteSubmissionV2(null, 20, 15, 8, 4, 4));
        assertThrows(BusinessValidationException.class, () ->
                calculator.validateCompleteSubmissionV2(30, null, 15, 8, 4, 4));
        assertThrows(BusinessValidationException.class, () ->
                calculator.validateCompleteSubmissionV2(30, 20, null, 8, 4, 4));
        assertThrows(BusinessValidationException.class, () ->
                calculator.validateCompleteSubmissionV2(30, 20, 15, null, 4, 4));
        assertThrows(BusinessValidationException.class, () ->
                calculator.validateCompleteSubmissionV2(30, 20, 15, 8, null, 4));
        assertThrows(BusinessValidationException.class, () ->
                calculator.validateCompleteSubmissionV2(30, 20, 15, 8, 4, null));

        // Valid complete submission passes
        assertDoesNotThrow(() ->
                calculator.validateCompleteSubmissionV2(30, 20, 15, 8, 4, 4));
    }

    @Test
    void testV4ManualCalculation_DirectSum() {
        // Commercial: 28, Cooperation: 20, Strategic: 16, Network: 8, Engagement: 4, Qualitative: 4
        // Total = 28 + 20 + 16 + 8 + 4 + 4 = 80 -> Rank B
        var result = calculator.calculateV4(28, 20, 16, 8, 4, 4);
        assertEquals(80, result.getRawScorableScore());
        assertEquals(80, result.getNormalizedTotalScore());
        assertEquals(100, result.getScorableBase());
        assertFalse(result.isNormalizationApplied());
        assertEquals(RelationshipAssessmentRank.B, result.getRank());
    }

    @Test
    void testV4RankBoundaries() {
        // Perfect 100 -> A
        var res100 = calculator.calculateV4(35, 25, 20, 10, 5, 5);
        assertEquals(100, res100.getNormalizedTotalScore());
        assertEquals(RelationshipAssessmentRank.A, res100.getRank());

        // 90 -> A
        var res90 = calculator.calculateV4(35, 25, 20, 10, 0, 0);
        assertEquals(90, res90.getNormalizedTotalScore());
        assertEquals(RelationshipAssessmentRank.A, res90.getRank());

        // 89 -> B
        var res89 = calculator.calculateV4(34, 25, 20, 10, 0, 0);
        assertEquals(89, res89.getNormalizedTotalScore());
        assertEquals(RelationshipAssessmentRank.B, res89.getRank());

        // 60 -> B
        var res60 = calculator.calculateV4(20, 15, 12, 7, 3, 3);
        assertEquals(60, res60.getNormalizedTotalScore());
        assertEquals(RelationshipAssessmentRank.B, res60.getRank());

        // 59 -> C
        var res59 = calculator.calculateV4(19, 15, 12, 7, 3, 3);
        assertEquals(59, res59.getNormalizedTotalScore());
        assertEquals(RelationshipAssessmentRank.C, res59.getRank());

        // 30 -> C
        var res30 = calculator.calculateV4(10, 10, 5, 3, 1, 1);
        assertEquals(30, res30.getNormalizedTotalScore());
        assertEquals(RelationshipAssessmentRank.C, res30.getRank());

        // 29 -> D
        var res29 = calculator.calculateV4(9, 10, 5, 3, 1, 1);
        assertEquals(29, res29.getNormalizedTotalScore());
        assertEquals(RelationshipAssessmentRank.D, res29.getRank());

        // 0 -> D
        var res0 = calculator.calculateV4(0, 0, 0, 0, 0, 0);
        assertEquals(0, res0.getNormalizedTotalScore());
        assertEquals(RelationshipAssessmentRank.D, res0.getRank());
    }

    @Test
    void testV4DraftRangeValidation_AllowsNullsAndChecksBounds() {
        // Nulls allowed in draft
        assertDoesNotThrow(() -> calculator.validateDraftRangesV4(null, null, null, null, null, null));
        assertDoesNotThrow(() -> calculator.validateDraftRangesV4(25, null, 15, null, 3, null));

        // Upper bound violations
        assertThrows(BusinessValidationException.class, () ->
                calculator.validateDraftRangesV4(36, 20, 15, 8, 4, 4));
        assertThrows(BusinessValidationException.class, () ->
                calculator.validateDraftRangesV4(30, 26, 15, 8, 4, 4));
        assertThrows(BusinessValidationException.class, () ->
                calculator.validateDraftRangesV4(30, 20, 21, 8, 4, 4));
        assertThrows(BusinessValidationException.class, () ->
                calculator.validateDraftRangesV4(30, 20, 15, 11, 4, 4));
        assertThrows(BusinessValidationException.class, () ->
                calculator.validateDraftRangesV4(30, 20, 15, 8, 6, 4));
        assertThrows(BusinessValidationException.class, () ->
                calculator.validateDraftRangesV4(30, 20, 15, 8, 4, 6));

        // Negative values
        assertThrows(BusinessValidationException.class, () ->
                calculator.validateDraftRangesV4(-1, 20, 15, 8, 4, 4));
    }

    @Test
    void testV4SubmissionValidation_RejectsMissingScores() {
        assertThrows(BusinessValidationException.class, () ->
                calculator.validateCompleteSubmissionV4(null, 20, 15, 8, 4, 4));
        assertThrows(BusinessValidationException.class, () ->
                calculator.validateCompleteSubmissionV4(30, null, 15, 8, 4, 4));
        assertThrows(BusinessValidationException.class, () ->
                calculator.validateCompleteSubmissionV4(30, 20, null, 8, 4, 4));
        assertThrows(BusinessValidationException.class, () ->
                calculator.validateCompleteSubmissionV4(30, 20, 15, null, 4, 4));
        assertThrows(BusinessValidationException.class, () ->
                calculator.validateCompleteSubmissionV4(30, 20, 15, 8, null, 4));
        assertThrows(BusinessValidationException.class, () ->
                calculator.validateCompleteSubmissionV4(30, 20, 15, 8, 4, null));

        // Valid complete submission passes
        assertDoesNotThrow(() ->
                calculator.validateCompleteSubmissionV4(30, 20, 15, 8, 4, 4));
    }
}
