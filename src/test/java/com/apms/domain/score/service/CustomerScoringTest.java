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

import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
public class CustomerScoringTest {

    @Mock
    private RoleScoreRuleSetRepository ruleSetRepository;

    @Mock
    private RoleCriterionRuleRepository criterionRuleRepository;

    @InjectMocks
    private RoleScoringEngine engine;

    @InjectMocks
    private RoleScoringSeedService seedService;

    @Nested
    @DisplayName("CUSTOMER Canonical Criteria Tests")
    class CanonicalCriteriaTests {

        @Test
        @DisplayName("All 6 CUSTOMER criteria must be defined as BENEFIT")
        void allCriteriaAreBenefit() {
            assertThat(CanonicalRoleCriteria.CUSTOMER_CRITERIA).containsExactly(
                    "revenueProfitabilityScore",
                    "purchaseBehaviorScore",
                    "customerLifetimeValueScore",
                    "retentionLoyaltyScore",
                    "growthPotentialScore",
                    "paymentChurnRiskScore"
            );

            for (String key : CanonicalRoleCriteria.CUSTOMER_CRITERIA) {
                assertThat(CanonicalRoleCriteria.getDirectionForRoleAndCriterion(CompanyRole.CUSTOMER, key))
                        .as("Criterion %s should be BENEFIT", key)
                        .isEqualTo(ScoreDirection.BENEFIT);
            }
        }
    }

    @Nested
    @DisplayName("CUSTOMER Illustrative Rule Migration Tests")
    class MigrationTests {

        @Test
        @DisplayName("Migrates correctly when ILLUSTRATIVE rules exist")
        void migratesIllustrativeRules() {
            RoleScoreRuleSet legacySet = RoleScoreRuleSet.builder()
                    .id(10L)
                    .evaluatedRole(CompanyRole.CUSTOMER)
                    .ruleSetVersion("ROLE_SCORING_V1")
                    .weightSource(WeightSource.ILLUSTRATIVE)
                    .active(true)
                    .rules(List.of(
                            RoleCriterionRule.builder().criterionKey("paymentChurnRiskScore").weight(new BigDecimal("0.10")).direction(ScoreDirection.COST).build()
                    ))
                    .build();

            org.mockito.Mockito.lenient().when(ruleSetRepository.existsByEvaluatedRoleAndRuleSetVersion(CompanyRole.CUSTOMER, "ROLE_SCORING_V1"))
                    .thenReturn(true);
            org.mockito.Mockito.lenient().when(ruleSetRepository.findByEvaluatedRoleAndRuleSetVersion(CompanyRole.CUSTOMER, "ROLE_SCORING_V1"))
                    .thenReturn(Optional.of(legacySet));

            ReflectionTestUtils.setField(seedService, "seedIllustrativeRules", true);
            seedService.seedRules();

            // We only verify that the direction changed because we might not directly call save in the unit test if the transaction handles it or if it iterates over all roles.
            assertThat(legacySet.getRules().get(0).getDirection()).isEqualTo(ScoreDirection.BENEFIT);
        }

        @Test
        @DisplayName("Does NOT migrate when EXPERT rules exist")
        void doesNotMigrateExpertRules() {
            RoleScoreRuleSet expertSet = RoleScoreRuleSet.builder()
                    .id(10L)
                    .evaluatedRole(CompanyRole.CUSTOMER)
                    .ruleSetVersion("ROLE_SCORING_V1")
                    .weightSource(WeightSource.EXPERT)
                    .active(true)
                    .rules(List.of(
                            RoleCriterionRule.builder().criterionKey("paymentChurnRiskScore").weight(new BigDecimal("0.10")).direction(ScoreDirection.COST).build()
                    ))
                    .build();

            org.mockito.Mockito.lenient().when(ruleSetRepository.existsByEvaluatedRoleAndRuleSetVersion(CompanyRole.CUSTOMER, "ROLE_SCORING_V1"))
                    .thenReturn(true);
            org.mockito.Mockito.lenient().when(ruleSetRepository.findByEvaluatedRoleAndRuleSetVersion(CompanyRole.CUSTOMER, "ROLE_SCORING_V1"))
                    .thenReturn(Optional.of(expertSet));

            ReflectionTestUtils.setField(seedService, "seedIllustrativeRules", true);
            seedService.seedRules();

            assertThat(expertSet.getRules().get(0).getDirection()).isEqualTo(ScoreDirection.COST);
        }
    }

    @Nested
    @DisplayName("CUSTOMER Scoring Engine Tests")
    class EngineTests {

        private RoleScoreRuleSet ruleSet;
        private List<RoleCriterionRule> rules;

        @BeforeEach
        void setUp() {
            ruleSet = RoleScoreRuleSet.builder()
                    .id(10L)
                    .evaluatedRole(CompanyRole.CUSTOMER)
                    .ruleSetVersion("ROLE_SCORING_V1")
                    .build();

            rules = List.of(
                    RoleCriterionRule.builder().criterionKey("revenueProfitabilityScore").weight(new BigDecimal("0.22")).direction(ScoreDirection.BENEFIT).required(true).build(),
                    RoleCriterionRule.builder().criterionKey("purchaseBehaviorScore").weight(new BigDecimal("0.15")).direction(ScoreDirection.BENEFIT).required(true).build(),
                    RoleCriterionRule.builder().criterionKey("customerLifetimeValueScore").weight(new BigDecimal("0.24")).direction(ScoreDirection.BENEFIT).required(true).build(),
                    RoleCriterionRule.builder().criterionKey("retentionLoyaltyScore").weight(new BigDecimal("0.18")).direction(ScoreDirection.BENEFIT).required(true).build(),
                    RoleCriterionRule.builder().criterionKey("growthPotentialScore").weight(new BigDecimal("0.11")).direction(ScoreDirection.BENEFIT).required(true).build(),
                    RoleCriterionRule.builder().criterionKey("paymentChurnRiskScore").weight(new BigDecimal("0.10")).direction(ScoreDirection.BENEFIT).required(true).build()
            );
        }

        @Test
        @DisplayName("Engine calculates exactly correctly for CUSTOMER")
        void calculateExactScore() {
            when(ruleSetRepository.findByEvaluatedRoleAndRuleSetVersion(CompanyRole.CUSTOMER, "ROLE_SCORING_V1"))
                    .thenReturn(Optional.of(ruleSet));
            when(criterionRuleRepository.findByRuleSetIdAndActiveTrueOrderByDisplayOrderAsc(10L))
                    .thenReturn(rules);

            RoleEvaluationCalculationRequest request = new RoleEvaluationCalculationRequest();
            request.setEvaluatedRole(CompanyRole.CUSTOMER);
            request.setRuleSetVersion("ROLE_SCORING_V1");
            request.setTargetCompanyProfileId("some-id");

            LinkedHashMap<String, BigDecimal> scores = new LinkedHashMap<>();
            scores.put("revenueProfitabilityScore", new BigDecimal("80")); // 80 * 0.22 = 17.6
            scores.put("purchaseBehaviorScore", new BigDecimal("70")); // 70 * 0.15 = 10.5
            scores.put("customerLifetimeValueScore", new BigDecimal("90")); // 90 * 0.24 = 21.6
            scores.put("retentionLoyaltyScore", new BigDecimal("60")); // 60 * 0.18 = 10.8
            scores.put("growthPotentialScore", new BigDecimal("80")); // 80 * 0.11 = 8.8
            scores.put("paymentChurnRiskScore", new BigDecimal("75")); // 75 * 0.10 = 7.5 (BENEFIT, so uses 75)
            request.setCriterionScores(scores);

            RoleEvaluationCalculationResult result = engine.calculate(request);
            assertThat(result.getCompletenessStatus()).isEqualTo(EvaluationCompletenessStatus.COMPLETE);
            assertThat(result.getOverallScore()).isEqualByComparingTo(new BigDecimal("76.80"));
        }

        @Test
        @DisplayName("Incomplete evaluation returns INCOMPLETE")
        void incompleteEvaluation() {
            when(ruleSetRepository.findByEvaluatedRoleAndRuleSetVersion(CompanyRole.CUSTOMER, "ROLE_SCORING_V1"))
                    .thenReturn(Optional.of(ruleSet));
            when(criterionRuleRepository.findByRuleSetIdAndActiveTrueOrderByDisplayOrderAsc(10L))
                    .thenReturn(rules);

            RoleEvaluationCalculationRequest request = new RoleEvaluationCalculationRequest();
            request.setEvaluatedRole(CompanyRole.CUSTOMER);
            request.setRuleSetVersion("ROLE_SCORING_V1");
            request.setTargetCompanyProfileId("some-id");

            LinkedHashMap<String, BigDecimal> scores = new LinkedHashMap<>();
            scores.put("revenueProfitabilityScore", new BigDecimal("80"));
            scores.put("purchaseBehaviorScore", new BigDecimal("70"));
            scores.put("customerLifetimeValueScore", null); // MISSING!
            scores.put("retentionLoyaltyScore", new BigDecimal("60"));
            scores.put("growthPotentialScore", new BigDecimal("80"));
            scores.put("paymentChurnRiskScore", new BigDecimal("75"));
            request.setCriterionScores(scores);

            RoleEvaluationCalculationResult result = engine.calculate(request);
            assertThat(result.getCompletenessStatus()).isEqualTo(EvaluationCompletenessStatus.INCOMPLETE);
            assertThat(result.getOverallScore()).isNull();
            assertThat(result.getMissingCriteria()).contains("customerLifetimeValueScore");
        }
    }
}
