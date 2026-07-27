package com.apms.domain.score.service;

import com.apms.domain.company.enums.CompanyRole;
import com.apms.domain.score.RoleCriterionRule;
import com.apms.domain.score.RoleScoreRuleSet;
import com.apms.domain.score.dto.RoleEvaluationCalculationRequest;
import com.apms.domain.score.dto.RoleEvaluationCalculationResult;
import com.apms.domain.score.engine.RoleScoringEngine;
import com.apms.domain.score.enums.EvaluationCompletenessStatus;
import com.apms.domain.score.enums.ScoreDirection;
import com.apms.domain.score.enums.WeightSource;
import com.apms.domain.score.registry.CanonicalRoleCriteria;
import com.apms.domain.score.repository.sql.RoleCriterionRuleRepository;
import com.apms.domain.score.repository.sql.RoleScoreRuleSetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class SupplierScoringTest {

    @Mock
    private RoleScoreRuleSetRepository ruleSetRepository;

    @Mock
    private RoleCriterionRuleRepository criterionRuleRepository;

    @InjectMocks
    private RoleScoringEngine engine;

    @InjectMocks
    private RoleScoringSeedService seedService;

    @Nested
    @DisplayName("SUPPLIER Canonical Criteria Tests")
    class CanonicalCriteriaTests {

        @Test
        @DisplayName("All 6 SUPPLIER criteria must be defined as BENEFIT")
        void allCriteriaAreBenefit() {
            assertThat(CanonicalRoleCriteria.SUPPLIER_CRITERIA).containsExactly(
                    "qualityPerformanceScore",
                    "costCompetitivenessScore",
                    "deliveryPerformanceScore",
                    "capacityFlexibilityScore",
                    "serviceResponsivenessScore",
                    "supplyRiskComplianceScore"
            );

            for (String key : CanonicalRoleCriteria.SUPPLIER_CRITERIA) {
                assertThat(CanonicalRoleCriteria.getDirectionForRoleAndCriterion(CompanyRole.SUPPLIER, key))
                        .as("Criterion %s should be BENEFIT", key)
                        .isEqualTo(ScoreDirection.BENEFIT);
            }
        }
    }

    @Nested
    @DisplayName("SUPPLIER Scoring Engine Tests")
    class EngineTests {

        private RoleScoreRuleSet ruleSet;
        private List<RoleCriterionRule> rules;

        @BeforeEach
        void setUp() {
            ruleSet = RoleScoreRuleSet.builder()
                    .id(20L)
                    .evaluatedRole(CompanyRole.SUPPLIER)
                    .ruleSetVersion("ROLE_SCORING_V1")
                    .build();

            rules = List.of(
                    RoleCriterionRule.builder().criterionKey("qualityPerformanceScore").weight(new BigDecimal("0.22")).direction(ScoreDirection.BENEFIT).required(true).build(),
                    RoleCriterionRule.builder().criterionKey("costCompetitivenessScore").weight(new BigDecimal("0.15")).direction(ScoreDirection.BENEFIT).required(true).build(),
                    RoleCriterionRule.builder().criterionKey("deliveryPerformanceScore").weight(new BigDecimal("0.21")).direction(ScoreDirection.BENEFIT).required(true).build(),
                    RoleCriterionRule.builder().criterionKey("capacityFlexibilityScore").weight(new BigDecimal("0.14")).direction(ScoreDirection.BENEFIT).required(true).build(),
                    RoleCriterionRule.builder().criterionKey("serviceResponsivenessScore").weight(new BigDecimal("0.10")).direction(ScoreDirection.BENEFIT).required(true).build(),
                    RoleCriterionRule.builder().criterionKey("supplyRiskComplianceScore").weight(new BigDecimal("0.18")).direction(ScoreDirection.BENEFIT).required(true).build()
            );
        }

        @Test
        @DisplayName("Engine calculates exactly correctly for SUPPLIER")
        void calculateExactScore() {
            when(ruleSetRepository.findByEvaluatedRoleAndRuleSetVersion(CompanyRole.SUPPLIER, "ROLE_SCORING_V1"))
                    .thenReturn(Optional.of(ruleSet));
            when(criterionRuleRepository.findByRuleSetIdAndActiveTrueOrderByDisplayOrderAsc(20L))
                    .thenReturn(rules);

            RoleEvaluationCalculationRequest request = new RoleEvaluationCalculationRequest();
            request.setEvaluatedRole(CompanyRole.SUPPLIER);
            request.setRuleSetVersion("ROLE_SCORING_V1");
            request.setTargetCompanyProfileId("some-id");
            request.setReferenceCompanyProfileId("owner-id");

            LinkedHashMap<String, BigDecimal> scores = new LinkedHashMap<>();
            scores.put("qualityPerformanceScore", new BigDecimal("80")); // 80 * 0.22 = 17.6
            scores.put("costCompetitivenessScore", new BigDecimal("70")); // 70 * 0.15 = 10.5
            scores.put("deliveryPerformanceScore", new BigDecimal("90")); // 90 * 0.21 = 18.9
            scores.put("capacityFlexibilityScore", new BigDecimal("60")); // 60 * 0.14 = 8.4
            scores.put("serviceResponsivenessScore", new BigDecimal("80")); // 80 * 0.10 = 8.0
            scores.put("supplyRiskComplianceScore", new BigDecimal("75")); // 75 * 0.18 = 13.5 (BENEFIT, so uses 75)
            request.setCriterionScores(scores);

            RoleEvaluationCalculationResult result = engine.calculate(request);
            assertThat(result.getCompletenessStatus()).isEqualTo(EvaluationCompletenessStatus.COMPLETE);

            // 17.6 + 10.5 + 18.9 + 8.4 + 8.0 + 13.5 = 76.90
            assertThat(result.getOverallScore()).isEqualByComparingTo(new BigDecimal("76.90"));
        }

        @Test
        @DisplayName("Incomplete evaluation returns INCOMPLETE")
        void incompleteEvaluation() {
            when(ruleSetRepository.findByEvaluatedRoleAndRuleSetVersion(CompanyRole.SUPPLIER, "ROLE_SCORING_V1"))
                    .thenReturn(Optional.of(ruleSet));
            when(criterionRuleRepository.findByRuleSetIdAndActiveTrueOrderByDisplayOrderAsc(20L))
                    .thenReturn(rules);

            RoleEvaluationCalculationRequest request = new RoleEvaluationCalculationRequest();
            request.setEvaluatedRole(CompanyRole.SUPPLIER);
            request.setRuleSetVersion("ROLE_SCORING_V1");
            request.setTargetCompanyProfileId("some-id");
            request.setReferenceCompanyProfileId("owner-id");

            LinkedHashMap<String, BigDecimal> scores = new LinkedHashMap<>();
            scores.put("qualityPerformanceScore", new BigDecimal("80"));
            scores.put("costCompetitivenessScore", new BigDecimal("70"));
            scores.put("deliveryPerformanceScore", null); // MISSING!
            scores.put("capacityFlexibilityScore", new BigDecimal("60"));
            scores.put("serviceResponsivenessScore", new BigDecimal("80"));
            scores.put("supplyRiskComplianceScore", new BigDecimal("75"));
            request.setCriterionScores(scores);

            RoleEvaluationCalculationResult result = engine.calculate(request);
            assertThat(result.getCompletenessStatus()).isEqualTo(EvaluationCompletenessStatus.INCOMPLETE);
            assertThat(result.getOverallScore()).isNull();
            assertThat(result.getMissingCriteria()).contains("deliveryPerformanceScore");
        }
    }
}
