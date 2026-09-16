package com.apms.domain.profile.assessment.policy;

import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.profile.assessment.RelationshipAssessmentRank;
import lombok.Builder;
import lombok.Getter;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class RelationshipScoreCalculator {

    public static final Set<Integer> ALLOWED_COOPERATION_SCORES = Set.of(0, 5, 10, 15, 20);
    public static final Set<Integer> ALLOWED_STRATEGIC_SCORES = Set.of(0, 5, 10, 15, 20);
    public static final Set<Integer> ALLOWED_NETWORK_SCORES = Set.of(0, 3, 6, 9, 12, 15);
    public static final Set<Integer> ALLOWED_ENGAGEMENT_SCORES = Set.of(0, 1, 2, 3, 4, 5);
    public static final Set<Integer> ALLOWED_QUALITATIVE_SCORES = Set.of(0, 1, 2, 3, 4, 5);

    // V5 Maximum Weights (Equal-Weight Guided Qualitative Scoring)
    public static final int V5_MAX_SCORE = 5;
    public static final int V5_MAX_RAW_SCORE = 30;

    // V4 Maximum Weights (Guided Qualitative Manual Scoring)
    public static final int V4_MAX_COMMERCIAL = 35;
    public static final int V4_MAX_COOPERATION = 25;
    public static final int V4_MAX_STRATEGIC = 20;
    public static final int V4_MAX_NETWORK = 10;
    public static final int V4_MAX_ENGAGEMENT = 5;
    public static final int V4_MAX_QUALITATIVE = 5;

    // V3 Maximum Weights
    public static final int V3_MAX_COMMERCIAL = 50;
    public static final int V3_MAX_ENGAGEMENT = 20;
    public static final int V3_MAX_NETWORK = 30;

    // V2 Maximum Weights
    public static final int V2_MAX_COMMERCIAL = 35;
    public static final int V2_MAX_COOPERATION = 25;
    public static final int V2_MAX_STRATEGIC = 20;
    public static final int V2_MAX_NETWORK = 10;
    public static final int V2_MAX_ENGAGEMENT = 5;
    public static final int V2_MAX_QUALITATIVE = 5;

    public void validateDraftRangesV3(
            Integer commercialAwarded,
            Integer engagement,
            Integer network) {

        if (commercialAwarded != null && (commercialAwarded < 0 || commercialAwarded > V3_MAX_COMMERCIAL)) {
            throw new BusinessValidationException("Commercial Awarded score must be between 0 and " + V3_MAX_COMMERCIAL + ", got: " + commercialAwarded);
        }
        if (engagement != null && (engagement < 0 || engagement > V3_MAX_ENGAGEMENT)) {
            throw new BusinessValidationException("Business Engagement score must be between 0 and " + V3_MAX_ENGAGEMENT + ", got: " + engagement);
        }
        if (network != null && (network < 0 || network > V3_MAX_NETWORK)) {
            throw new BusinessValidationException("Relationship Network score must be between 0 and " + V3_MAX_NETWORK + ", got: " + network);
        }
    }

    public void validateCompleteSubmissionV3(
            Integer commercialAwarded,
            Integer engagement,
            Integer network) {

        if (commercialAwarded == null) {
            throw new BusinessValidationException("Commercial Awarded score is required for submission.");
        }
        if (engagement == null) {
            throw new BusinessValidationException("Business Engagement score is required for submission.");
        }
        if (network == null) {
            throw new BusinessValidationException("Relationship Network score is required for submission.");
        }

        validateDraftRangesV3(commercialAwarded, engagement, network);
    }

    public CalculationResult calculateV3(
            int commercialAwarded,
            int engagement,
            int network) {

        validateCompleteSubmissionV3(commercialAwarded, engagement, network);

        int totalScore = commercialAwarded + engagement + network;
        totalScore = Math.max(0, Math.min(100, totalScore));

        RelationshipAssessmentRank rank = RelationshipAssessmentRank.fromScore(totalScore);

        return CalculationResult.builder()
                .rawScorableScore(totalScore)
                .normalizedTotalScore(totalScore)
                .scorableBase(100)
                .normalizationApplied(false)
                .rank(rank)
                .build();
    }

    public void validateDraftRangesV4(
            Integer commercialAwarded,
            Integer cooperation,
            Integer strategic,
            Integer network,
            Integer engagement,
            Integer qualitative) {

        if (commercialAwarded != null && (commercialAwarded < 0 || commercialAwarded > V4_MAX_COMMERCIAL)) {
            throw new BusinessValidationException("Commercial score must be between 0 and " + V4_MAX_COMMERCIAL + ", got: " + commercialAwarded);
        }
        if (cooperation != null && (cooperation < 0 || cooperation > V4_MAX_COOPERATION)) {
            throw new BusinessValidationException("Interaction & Cooperation score must be between 0 and " + V4_MAX_COOPERATION + ", got: " + cooperation);
        }
        if (strategic != null && (strategic < 0 || strategic > V4_MAX_STRATEGIC)) {
            throw new BusinessValidationException("Strategic Relationship score must be between 0 and " + V4_MAX_STRATEGIC + ", got: " + strategic);
        }
        if (network != null && (network < 0 || network > V4_MAX_NETWORK)) {
            throw new BusinessValidationException("Relationship Network score must be between 0 and " + V4_MAX_NETWORK + ", got: " + network);
        }
        if (engagement != null && (engagement < 0 || engagement > V4_MAX_ENGAGEMENT)) {
            throw new BusinessValidationException("Business Engagement score must be between 0 and " + V4_MAX_ENGAGEMENT + ", got: " + engagement);
        }
        if (qualitative != null && (qualitative < 0 || qualitative > V4_MAX_QUALITATIVE)) {
            throw new BusinessValidationException("Qualitative Assessment score must be between 0 and " + V4_MAX_QUALITATIVE + ", got: " + qualitative);
        }
    }

    public void validateCompleteSubmissionV4(
            Integer commercialAwarded,
            Integer cooperation,
            Integer strategic,
            Integer network,
            Integer engagement,
            Integer qualitative) {

        if (commercialAwarded == null) {
            throw new BusinessValidationException("Commercial score is required for submission.");
        }
        if (cooperation == null) {
            throw new BusinessValidationException("Interaction & Cooperation score is required for submission.");
        }
        if (strategic == null) {
            throw new BusinessValidationException("Strategic Relationship score is required for submission.");
        }
        if (network == null) {
            throw new BusinessValidationException("Relationship Network score is required for submission.");
        }
        if (engagement == null) {
            throw new BusinessValidationException("Business Engagement score is required for submission.");
        }
        if (qualitative == null) {
            throw new BusinessValidationException("Qualitative Assessment score is required for submission.");
        }

        validateDraftRangesV4(commercialAwarded, cooperation, strategic, network, engagement, qualitative);
    }

    public CalculationResult calculateV4(
            int commercialAwarded,
            int cooperation,
            int strategic,
            int network,
            int engagement,
            int qualitative) {

        validateCompleteSubmissionV4(commercialAwarded, cooperation, strategic, network, engagement, qualitative);

        int totalScore = commercialAwarded + cooperation + strategic + network + engagement + qualitative;
        totalScore = Math.max(0, Math.min(100, totalScore));

        RelationshipAssessmentRank rank = RelationshipAssessmentRank.fromScore(totalScore);

        return CalculationResult.builder()
                .rawScorableScore(totalScore)
                .normalizedTotalScore(totalScore)
                .scorableBase(100)
                .normalizationApplied(false)
                .rank(rank)
                .build();
    }

    public void validateDraftRangesV5(
            Integer commercialAwarded,
            Integer cooperation,
            Integer strategic,
            Integer network,
            Integer engagement,
            Integer trust) {

        if (commercialAwarded != null && (commercialAwarded < 0 || commercialAwarded > V5_MAX_SCORE)) {
            throw new BusinessValidationException("Commercial Relationship score must be between 0 and " + V5_MAX_SCORE + ", got: " + commercialAwarded);
        }
        if (cooperation != null && (cooperation < 0 || cooperation > V5_MAX_SCORE)) {
            throw new BusinessValidationException("Interaction & Cooperation score must be between 0 and " + V5_MAX_SCORE + ", got: " + cooperation);
        }
        if (strategic != null && (strategic < 0 || strategic > V5_MAX_SCORE)) {
            throw new BusinessValidationException("Strategic Importance score must be between 0 and " + V5_MAX_SCORE + ", got: " + strategic);
        }
        if (network != null && (network < 0 || network > V5_MAX_SCORE)) {
            throw new BusinessValidationException("Relationship Network score must be between 0 and " + V5_MAX_SCORE + ", got: " + network);
        }
        if (engagement != null && (engagement < 0 || engagement > V5_MAX_SCORE)) {
            throw new BusinessValidationException("Business Engagement score must be between 0 and " + V5_MAX_SCORE + ", got: " + engagement);
        }
        if (trust != null && (trust < 0 || trust > V5_MAX_SCORE)) {
            throw new BusinessValidationException("Trust & Reliability score must be between 0 and " + V5_MAX_SCORE + ", got: " + trust);
        }
    }

    public void validateCompleteSubmissionV5(
            Integer commercialAwarded,
            Integer cooperation,
            Integer strategic,
            Integer network,
            Integer engagement,
            Integer trust) {

        if (commercialAwarded == null) {
            throw new BusinessValidationException("Commercial Relationship score is required for submission.");
        }
        if (cooperation == null) {
            throw new BusinessValidationException("Interaction & Cooperation score is required for submission.");
        }
        if (strategic == null) {
            throw new BusinessValidationException("Strategic Importance score is required for submission.");
        }
        if (network == null) {
            throw new BusinessValidationException("Relationship Network score is required for submission.");
        }
        if (engagement == null) {
            throw new BusinessValidationException("Business Engagement score is required for submission.");
        }
        if (trust == null) {
            throw new BusinessValidationException("Trust & Reliability score is required for submission.");
        }

        validateDraftRangesV5(commercialAwarded, cooperation, strategic, network, engagement, trust);
    }

    public CalculationResult calculateV5(
            int commercialAwarded,
            int cooperation,
            int strategic,
            int network,
            int engagement,
            int trust) {

        validateCompleteSubmissionV5(commercialAwarded, cooperation, strategic, network, engagement, trust);

        int rawScore = commercialAwarded + cooperation + strategic + network + engagement + trust;
        rawScore = Math.max(0, Math.min(V5_MAX_RAW_SCORE, rawScore));

        double exactNormalizedScore = (rawScore * 100.0) / (double) V5_MAX_RAW_SCORE;
        int displayScore = (int) Math.round(exactNormalizedScore);
        displayScore = Math.max(0, Math.min(100, displayScore));

        RelationshipAssessmentRank rank = RelationshipAssessmentRank.fromExactScore(exactNormalizedScore);

        return CalculationResult.builder()
                .rawScorableScore(rawScore)
                .normalizedTotalScore(displayScore)
                .exactNormalizedScore(exactNormalizedScore)
                .scorableBase(V5_MAX_RAW_SCORE)
                .normalizationApplied(true)
                .rank(rank)
                .build();
    }

    @Getter
    @Builder
    public static class CalculationResult {
        private final int rawScorableScore;
        private final int normalizedTotalScore;
        private final double exactNormalizedScore;
        private final int scorableBase;
        private final boolean normalizationApplied;
        private final RelationshipAssessmentRank rank;
    }

    public void validateDraftRangesV2(
            Integer commercialAwarded,
            Integer cooperation,
            Integer strategic,
            Integer network,
            Integer engagement,
            Integer qualitative) {

        if (commercialAwarded != null && (commercialAwarded < 0 || commercialAwarded > V2_MAX_COMMERCIAL)) {
            throw new BusinessValidationException("Commercial Awarded score must be between 0 and " + V2_MAX_COMMERCIAL + ", got: " + commercialAwarded);
        }
        if (cooperation != null && (cooperation < 0 || cooperation > V2_MAX_COOPERATION)) {
            throw new BusinessValidationException("Interaction & Cooperation score must be between 0 and " + V2_MAX_COOPERATION + ", got: " + cooperation);
        }
        if (strategic != null && (strategic < 0 || strategic > V2_MAX_STRATEGIC)) {
            throw new BusinessValidationException("Strategic Relationship score must be between 0 and " + V2_MAX_STRATEGIC + ", got: " + strategic);
        }
        if (network != null && (network < 0 || network > V2_MAX_NETWORK)) {
            throw new BusinessValidationException("Relationship Network score must be between 0 and " + V2_MAX_NETWORK + ", got: " + network);
        }
        if (engagement != null && (engagement < 0 || engagement > V2_MAX_ENGAGEMENT)) {
            throw new BusinessValidationException("Business Engagement score must be between 0 and " + V2_MAX_ENGAGEMENT + ", got: " + engagement);
        }
        if (qualitative != null && (qualitative < 0 || qualitative > V2_MAX_QUALITATIVE)) {
            throw new BusinessValidationException("Qualitative Assessment score must be between 0 and " + V2_MAX_QUALITATIVE + ", got: " + qualitative);
        }
    }

    public void validateCompleteSubmissionV2(
            Integer commercialAwarded,
            Integer cooperation,
            Integer strategic,
            Integer network,
            Integer engagement,
            Integer qualitative) {

        if (commercialAwarded == null) {
            throw new BusinessValidationException("Commercial Awarded score is required for submission.");
        }
        if (cooperation == null) {
            throw new BusinessValidationException("Interaction & Cooperation score is required for submission.");
        }
        if (strategic == null) {
            throw new BusinessValidationException("Strategic Relationship score is required for submission.");
        }
        if (network == null) {
            throw new BusinessValidationException("Relationship Network score is required for submission.");
        }
        if (engagement == null) {
            throw new BusinessValidationException("Business Engagement score is required for submission.");
        }
        if (qualitative == null) {
            throw new BusinessValidationException("Qualitative Assessment score is required for submission.");
        }

        validateDraftRangesV2(commercialAwarded, cooperation, strategic, network, engagement, qualitative);
    }

    public CalculationResult calculateV2(
            int commercialAwarded,
            int cooperation,
            int strategic,
            int network,
            int engagement,
            int qualitative) {

        validateCompleteSubmissionV2(commercialAwarded, cooperation, strategic, network, engagement, qualitative);

        int totalScore = commercialAwarded + cooperation + strategic + network + engagement + qualitative;
        totalScore = Math.max(0, Math.min(100, totalScore));

        RelationshipAssessmentRank rank = RelationshipAssessmentRank.fromScore(totalScore);

        return CalculationResult.builder()
                .rawScorableScore(totalScore)
                .normalizedTotalScore(totalScore)
                .scorableBase(100)
                .normalizationApplied(false)
                .rank(rank)
                .build();
    }

    public void validateRubrics(
            Integer cooperation,
            Integer strategic,
            Integer network,
            Integer engagement,
            Integer qualitative) {

        if (cooperation == null || !ALLOWED_COOPERATION_SCORES.contains(cooperation)) {
            throw new BusinessValidationException("Cooperation Quality score must be one of " + ALLOWED_COOPERATION_SCORES + ", got: " + cooperation);
        }
        if (strategic == null || !ALLOWED_STRATEGIC_SCORES.contains(strategic)) {
            throw new BusinessValidationException("Strategic Relationship score must be one of " + ALLOWED_STRATEGIC_SCORES + ", got: " + strategic);
        }
        if (network == null || !ALLOWED_NETWORK_SCORES.contains(network)) {
            throw new BusinessValidationException("Relationship Network score must be one of " + ALLOWED_NETWORK_SCORES + ", got: " + network);
        }
        if (engagement == null || !ALLOWED_ENGAGEMENT_SCORES.contains(engagement)) {
            throw new BusinessValidationException("Business Engagement score must be one of " + ALLOWED_ENGAGEMENT_SCORES + ", got: " + engagement);
        }
        if (qualitative == null || !ALLOWED_QUALITATIVE_SCORES.contains(qualitative)) {
            throw new BusinessValidationException("Qualitative Assessment score must be one of " + ALLOWED_QUALITATIVE_SCORES + ", got: " + qualitative);
        }
    }

    public CalculationResult calculate(
            int commercialScore,
            int cooperation,
            int strategic,
            int network,
            int engagement,
            int qualitative,
            int scorableBase,
            boolean normalizationApplied) {

        validateRubrics(cooperation, strategic, network, engagement, qualitative);

        int humanScore = cooperation + strategic + network + engagement + qualitative;
        int rawScore = commercialScore + humanScore;

        int finalScore;
        if (normalizationApplied && scorableBase > 0 && scorableBase != 100) {
            finalScore = (int) Math.round(((double) rawScore / (double) scorableBase) * 100.0);
        } else {
            finalScore = rawScore;
        }

        // Clamp between 0 and 100 for safety
        finalScore = Math.max(0, Math.min(100, finalScore));

        RelationshipAssessmentRank rank = RelationshipAssessmentRank.fromScore(finalScore);

        return CalculationResult.builder()
                .rawScorableScore(rawScore)
                .normalizedTotalScore(finalScore)
                .scorableBase(scorableBase)
                .normalizationApplied(normalizationApplied)
                .rank(rank)
                .build();
    }
}
