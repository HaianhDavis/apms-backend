package com.apms.domain.score.engine;

import com.apms.domain.score.RoleCriterionRule;
import com.apms.domain.score.RoleScoreRuleSet;
import com.apms.domain.score.dto.RoleEvaluationCalculationRequest;
import com.apms.domain.score.dto.RoleEvaluationCalculationResult;
import com.apms.domain.score.enums.EvaluationCompletenessStatus;
import com.apms.domain.score.enums.ScoreDirection;
import com.apms.domain.score.registry.CanonicalRoleCriteria;
import com.apms.domain.score.repository.sql.RoleCriterionRuleRepository;
import com.apms.domain.score.repository.sql.RoleScoreRuleSetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RoleScoringEngine {

    private final RoleScoreRuleSetRepository ruleSetRepository;
    private final RoleCriterionRuleRepository criterionRuleRepository;

    public RoleEvaluationCalculationResult calculate(RoleEvaluationCalculationRequest request) {
        RoleScoreRuleSet ruleSet = ruleSetRepository.findByEvaluatedRoleAndRuleSetVersion(
                        request.getEvaluatedRole(), request.getRuleSetVersion())
                .orElseThrow(() -> new IllegalArgumentException("No active score rule set was found for role: " + request.getEvaluatedRole()));

        List<RoleCriterionRule> activeRules = criterionRuleRepository.findByRuleSetIdAndActiveTrueOrderByDisplayOrderAsc(ruleSet.getId());
        
        List<String> expectedCriteria = CanonicalRoleCriteria.getCriteriaForRole(request.getEvaluatedRole());
        
        if (activeRules.size() != 6) {
            throw new IllegalStateException("Exactly six active criterion rules must exist for rule set: " + ruleSet.getRuleSetVersion());
        }

        List<String> ruleKeys = activeRules.stream().map(RoleCriterionRule::getCriterionKey).toList();
        if (!ruleKeys.containsAll(expectedCriteria) || !expectedCriteria.containsAll(ruleKeys)) {
            throw new IllegalStateException("Criterion keys must exactly match CanonicalRoleCriteria for the evaluated role");
        }

        // Duplicates are naturally impossible because of DB unique constraint, but we check if the set size is different
        if (ruleKeys.stream().distinct().count() != 6) {
            throw new IllegalStateException("No duplicate criterion keys are allowed");
        }
        
        BigDecimal totalWeight = BigDecimal.ZERO;
        for (RoleCriterionRule rule : activeRules) {
            BigDecimal weight = rule.getWeight();
            if (weight == null || weight.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalStateException("Every weight must be greater than zero");
            }
            if (weight.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalStateException("Every weight must be less than or equal to one");
            }
            totalWeight = totalWeight.add(weight);
        }
        
        if (totalWeight.compareTo(new BigDecimal("1.00")) != 0) {
            throw new IllegalStateException("Criterion weights must sum to 1.00 for rule set: " + ruleSet.getRuleSetVersion());
        }

        LinkedHashMap<String, BigDecimal> criterionScores = new LinkedHashMap<>();
        LinkedHashMap<String, BigDecimal> normalizedScores = new LinkedHashMap<>();
        LinkedHashMap<String, BigDecimal> weightsUsed = new LinkedHashMap<>();
        List<String> missingCriteria = new ArrayList<>();

        BigDecimal totalWeightedScore = BigDecimal.ZERO;
        boolean isIncomplete = false;

        for (RoleCriterionRule rule : activeRules) {
            String key = rule.getCriterionKey();

            if (!CanonicalRoleCriteria.isValidCriterionForRole(request.getEvaluatedRole(), key)) {
                throw new IllegalArgumentException("Unknown criterion for evaluated role: " + key);
            }

            BigDecimal score = request.getCriterionScores().get(key);
            criterionScores.put(key, score);
            weightsUsed.put(key, rule.getWeight());

            if (score == null) {
                if (Boolean.TRUE.equals(rule.getRequired())) {
                    missingCriteria.add(key);
                    isIncomplete = true;
                }
                normalizedScores.put(key, null);
                continue;
            }

            if (score.compareTo(BigDecimal.ZERO) < 0 || score.compareTo(new BigDecimal("100")) > 0) {
                throw new IllegalArgumentException("Criterion score must be between 0 and 100: " + key);
            }

            BigDecimal normalizedScore = normalizeScore(score, rule.getDirection());
            normalizedScores.put(key, normalizedScore);

            totalWeightedScore = totalWeightedScore.add(normalizedScore.multiply(rule.getWeight()));
        }

        // Check for unknown keys in request that are not in rule set
        for (String reqKey : request.getCriterionScores().keySet()) {
            if (activeRules.stream().noneMatch(r -> r.getCriterionKey().equals(reqKey))) {
                throw new IllegalArgumentException("Unknown criterion for evaluated role: " + reqKey);
            }
        }

        BigDecimal overallScore = null;
        EvaluationCompletenessStatus status = EvaluationCompletenessStatus.INCOMPLETE;

        if (!isIncomplete) {
            status = EvaluationCompletenessStatus.COMPLETE;
            overallScore = totalWeightedScore.setScale(2, RoundingMode.HALF_UP);
        }

        return RoleEvaluationCalculationResult.builder()
                .evaluatedRole(request.getEvaluatedRole())
                .criterionScores(criterionScores)
                .normalizedCriterionScores(normalizedScores)
                .weightsUsed(weightsUsed)
                .overallScore(overallScore)
                .completenessStatus(status)
                .missingCriteria(missingCriteria)
                .ruleSetVersion(ruleSet.getRuleSetVersion())
                .weightVersion(ruleSet.getWeightVersion())
                .weightingMethod(ruleSet.getWeightingMethod())
                .weightSource(ruleSet.getWeightSource())
                .build();
    }

    private BigDecimal normalizeScore(BigDecimal rawScore, ScoreDirection direction) {
        if (direction == ScoreDirection.COST) {
            return new BigDecimal("100").subtract(rawScore);
        }
        // BENEFIT or THREAT
        return rawScore;
    }
}
