package com.apms.domain.score.service;

import com.apms.common.enums.RelationshipType;
import com.apms.domain.score.ScoreRule;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ScoreServiceTest {

    private ScoreService scoreService;

    @BeforeEach
    void setUp() {
        scoreService = new ScoreService(null, null, null, null, new ObjectMapper());
    }

    // ─────────────────────────────────────────────
    // resolveWeights — DB rules → weight map
    // ─────────────────────────────────────────────

    @Test
    void resolveWeights_withActiveRules_returnsWeightsFromDB() {
        List<ScoreRule> rules = List.of(
                buildRule("Partner Fit Rule", "PARTNER_FIT", 40),
                buildRule("Competition Rule", "COMPETITION", 15),
                buildRule("Risk Rule", "RISK", 25),
                buildRule("Relationship Rule", "RELATIONSHIP", 20)
        );

        Map<String, Integer> weights = scoreService.resolveWeights(rules);

        assertEquals(40, weights.get("PARTNER_FIT"));
        assertEquals(15, weights.get("COMPETITION"));
        assertEquals(25, weights.get("RISK"));
        assertEquals(20, weights.get("RELATIONSHIP"));
    }

    @Test
    void resolveWeights_emptyRules_returnsDefaults() {
        Map<String, Integer> weights = scoreService.resolveWeights(List.of());

        assertEquals(30, weights.get("PARTNER_FIT"));
        assertEquals(20, weights.get("COMPETITION"));
        assertEquals(20, weights.get("RISK"));
        assertEquals(30, weights.get("RELATIONSHIP"));
    }

    @Test
    void resolveWeights_partialRules_onlyIncludesPresentCategories() {
        List<ScoreRule> rules = List.of(
                buildRule("Partner Rule", "PARTNER_FIT", 50),
                buildRule("Risk Rule", "RISK", 50)
        );

        Map<String, Integer> weights = scoreService.resolveWeights(rules);

        assertEquals(50, weights.get("PARTNER_FIT"));
        assertEquals(50, weights.get("RISK"));
        assertNull(weights.get("COMPETITION"));
        assertNull(weights.get("RELATIONSHIP"));
    }

    // ─────────────────────────────────────────────
    // computeWeightedTotal — formula correctness
    // ─────────────────────────────────────────────

    @Test
    void computeWeightedTotal_equalWeights_averagesCorrectly() {
        Map<String, Integer> weights = Map.of(
                "PARTNER_FIT", 25,
                "COMPETITION", 25,
                "RISK", 25,
                "RELATIONSHIP", 25
        );

        // partnerFit=80, competition=20, risk=10, strength=90
        // total = (80*25 + (100-20)*25 + (100-10)*25 + 90*25) / 100
        //       = (2000 + 2000 + 2250 + 2250) / 100 = 8500 / 100 = 85
        int total = scoreService.computeWeightedTotal(80, 20, 10, 90, weights);

        assertEquals(85, total);
    }

    @Test
    void computeWeightedTotal_customWeights_appliesCorrectly() {
        Map<String, Integer> weights = Map.of(
                "PARTNER_FIT", 40,
                "COMPETITION", 10,
                "RISK", 10,
                "RELATIONSHIP", 40
        );

        // partnerFit=100, competition=0, risk=0, strength=100
        // total = (100*40 + 100*10 + 100*10 + 100*40) / 100
        //       = (4000 + 1000 + 1000 + 4000) / 100 = 100
        int total = scoreService.computeWeightedTotal(100, 0, 0, 100, weights);

        assertEquals(100, total);
    }

    @Test
    void computeWeightedTotal_asymmetricWeights_usesTotalWeight() {
        Map<String, Integer> weights = Map.of(
                "PARTNER_FIT", 60,
                "COMPETITION", 20,
                "RISK", 10,
                "RELATIONSHIP", 10
        );

        // partnerFit=100, competition=50, risk=50, strength=50
        // totalWeight = 100
        // total = (100*60 + 50*20 + 50*10 + 50*10) / 100
        //       = (6000 + 1000 + 500 + 500) / 100 = 80
        int total = scoreService.computeWeightedTotal(100, 50, 50, 50, weights);

        assertEquals(80, total);
    }

    // ─────────────────────────────────────────────
    // computePartnerFitScore — with rule conditions
    // ─────────────────────────────────────────────

    @Test
    void computePartnerFitScore_partnerType_highConfidence() {
        int score = scoreService.computePartnerFitScore(
                0.9, RelationshipType.PARTNER_WITH, Map.of());

        // base = 0.9 * 60 + 25 = 79
        assertEquals(79, score);
    }

    @Test
    void computePartnerFitScore_competitorType_lowConfidence() {
        int score = scoreService.computePartnerFitScore(
                0.3, RelationshipType.COMPETITOR_OF, Map.of());

        // base = 0.3 * 60 = 18, no bonus
        assertEquals(18, score);
    }

    @Test
    void computePartnerFitScore_withMinConfidenceCondition_penalizesBelowThreshold() {
        // ruleConditionJson: {"minConfidence": 0.8}
        int scoreWithCondition = scoreService.computePartnerFitScore(
                0.5, RelationshipType.POTENTIAL_PARTNER_OF,
                Map.of("minConfidence", 0.8));

        int scoreWithoutCondition = scoreService.computePartnerFitScore(
                0.5, RelationshipType.POTENTIAL_PARTNER_OF, Map.of());

        // Without condition: base = 0.5*60 + 25 = 55
        // With condition (0.8): penalized = 55 * (0.5/0.8) = 34
        assertTrue(scoreWithCondition < scoreWithoutCondition,
                "minConfidence condition should penalize low-confidence scores");
        assertEquals(34, scoreWithCondition);
    }

    // ─────────────────────────────────────────────
    // computeCompetitionLevel — with rule conditions
    // ─────────────────────────────────────────────

    @Test
    void computeCompetitionLevel_competitor_highConfidence() {
        int score = scoreService.computeCompetitionLevel(
                0.9, RelationshipType.COMPETITOR_OF, Map.of());

        // min(100, 0.9*70 + 20) = min(100, 83) = 83
        assertEquals(83, score);
    }

    @Test
    void computeCompetitionLevel_partnerType() {
        int score = scoreService.computeCompetitionLevel(
                0.8, RelationshipType.PARTNER_WITH, Map.of());

        // (1.0-0.8)*40 = 7 due to floating point, then min(50, 7) = 7
        assertEquals(7, score);
    }

    @Test
    void computeCompetitionLevel_withMaxScoreCondition_capsResult() {
        int scoreWithCap = scoreService.computeCompetitionLevel(
                0.9, RelationshipType.COMPETITOR_OF,
                Map.of("maxScore", 50));

        int scoreWithoutCap = scoreService.computeCompetitionLevel(
                0.9, RelationshipType.COMPETITOR_OF, Map.of());

        // Without cap: 83, With cap: min(83, 50) = 50
        assertEquals(50, scoreWithCap);
        assertEquals(83, scoreWithoutCap);
    }

    // ─────────────────────────────────────────────
    // End-to-end: rules from DB → final total
    // ─────────────────────────────────────────────

    @Test
    void endToEnd_customWeightsFromDB_reflectedInTotal() {
        // Simulate: Owner updated weights via ScoreRulesViewer
        List<ScoreRule> rules = List.of(
                buildRule("Partner Rule", "PARTNER_FIT", 50),
                buildRule("Competition Rule", "COMPETITION", 10),
                buildRule("Risk Rule", "RISK", 10),
                buildRule("Relationship Rule", "RELATIONSHIP", 30)
        );

        Map<String, Integer> weights = scoreService.resolveWeights(rules);

        Double confidence = 0.85;
        RelationshipType relType = RelationshipType.POTENTIAL_PARTNER_OF;

        int partnerFit = scoreService.computePartnerFitScore(confidence, relType, Map.of());
        int competition = scoreService.computeCompetitionLevel(confidence, relType, Map.of());
        int risk = scoreService.computeRiskLevel(confidence, relType, Map.of());
        int strength = scoreService.computeRelationshipStrength(confidence, relType, Map.of());
        int total = scoreService.computeWeightedTotal(partnerFit, competition, risk, strength, weights);

        // partnerFit = (int)(0.85*60) + 25 = 76
        // competition = (int)((1.0-0.85)*40) = 6 (IEEE 754 rounding), min(50,6) = 6
        // risk = (int)((1.0-0.85)*40) = 6
        // strength = (int)(0.85*70) + 10 = 69
        // total = (76*50 + 94*10 + 94*10 + 69*30) / 100 = 7760/100 = 78
        assertEquals(76, partnerFit);
        assertEquals(6, competition);
        assertEquals(6, risk);
        assertEquals(69, strength);
        assertEquals(78, total);
    }

    @Test
    void endToEnd_differentWeights_producesDifferentTotal() {
        List<ScoreRule> rulesA = List.of(
                buildRule("P", "PARTNER_FIT", 70),
                buildRule("C", "COMPETITION", 10),
                buildRule("R", "RISK", 10),
                buildRule("S", "RELATIONSHIP", 10)
        );
        List<ScoreRule> rulesB = List.of(
                buildRule("P", "PARTNER_FIT", 10),
                buildRule("C", "COMPETITION", 10),
                buildRule("R", "RISK", 10),
                buildRule("S", "RELATIONSHIP", 70)
        );

        Map<String, Integer> weightsA = scoreService.resolveWeights(rulesA);
        Map<String, Integer> weightsB = scoreService.resolveWeights(rulesB);

        // Same inputs, different weights → different totals
        int totalA = scoreService.computeWeightedTotal(80, 20, 10, 60, weightsA);
        int totalB = scoreService.computeWeightedTotal(80, 20, 10, 60, weightsB);

        assertNotEquals(totalA, totalB,
                "Different rule weights from DB must produce different totals");
    }

    // ─────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────

    private ScoreRule buildRule(String name, String category, int weight) {
        return ScoreRule.builder()
                .id(1L)
                .ruleName(name)
                .ruleCategory(category)
                .weight(weight)
                .isActive(true)
                .build();
    }
}
