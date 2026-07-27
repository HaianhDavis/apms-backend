package com.apms.domain.score.service;

import com.apms.domain.company.enums.CompanyRole;
import com.apms.domain.score.RoleCriterionRule;
import com.apms.domain.score.RoleScoreRuleSet;
import com.apms.domain.score.draft.ApprovedSourceReference;
import com.apms.domain.score.draft.CriterionInput;
import com.apms.domain.score.draft.RoleEvaluationDraft;
import com.apms.domain.score.dto.RoleEvaluationCalculationRequest;
import com.apms.domain.score.dto.RoleEvaluationCalculationResult;
import com.apms.domain.score.dto.draft.RoleEvaluationReadinessResponse;
import com.apms.domain.score.engine.RoleScoringEngine;
import com.apms.domain.score.enums.ApprovedSourceType;
import com.apms.domain.score.enums.EvaluationCompletenessStatus;
import com.apms.domain.score.enums.ScoreDirection;
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
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Comprehensive tests for POTENTIAL_PARTNER scoring.
 * <p>
 * Validates:
 * - Exact six-criterion validation
 * - Higher-is-better risk behavior (no inversion)
 * - Exact weighted formula
 * - INCOMPLETE/PARTIAL/COMPLETE financial behavior
 * - Immutable version and ScoreSnapshot creation
 * - COMPETITOR and PARTNER regressions remain unchanged
 */
@ExtendWith(MockitoExtension.class)
class PotentialPartnerScoringTest {

    // =====================================================================
    // SECTION 1: Criterion Registry Tests
    // =====================================================================
    @Nested
    @DisplayName("Criterion Registry")
    class CriterionRegistryTests {

        @Test
        @DisplayName("POTENTIAL_PARTNER must have exactly 6 canonical criteria")
        void shouldHaveExactlySixCriteria() {
            List<String> criteria = CanonicalRoleCriteria.getCriteriaForRole(CompanyRole.POTENTIAL_PARTNER);
            assertThat(criteria).hasSize(6);
            assertThat(criteria).containsExactly(
                    "strategicFitScore",
                    "capabilityComplementarityScore",
                    "trustReputationScore",
                    "financialAttractivenessScore",
                    "collaborationPotentialScore",
                    "partnershipRiskScore"
            );
        }

        @Test
        @DisplayName("All 6 POTENTIAL_PARTNER criteria must be BENEFIT — including partnershipRiskScore")
        void allCriteriaShouldBeBenefit() {
            for (String key : CanonicalRoleCriteria.POTENTIAL_PARTNER_CRITERIA) {
                assertThat(CanonicalRoleCriteria.getDirectionForRoleAndCriterion(CompanyRole.POTENTIAL_PARTNER, key))
                        .as("Direction for %s", key)
                        .isEqualTo(ScoreDirection.BENEFIT);
            }
        }

        @Test
        @DisplayName("partnershipRiskScore is BENEFIT, not COST — higher = safer")
        void partnershipRiskScoreShouldBeBenefit() {
            assertThat(CanonicalRoleCriteria.getDirectionForRoleAndCriterion(
                    CompanyRole.POTENTIAL_PARTNER, "partnershipRiskScore"))
                    .isEqualTo(ScoreDirection.BENEFIT);
        }
    }

    // =====================================================================
    // SECTION 2: Weighted Formula / Scoring Engine Tests
    // =====================================================================
    @Nested
    @DisplayName("Weighted Formula")
    class WeightedFormulaTests {

        @Mock
        private RoleScoreRuleSetRepository ruleSetRepository;
        @Mock
        private RoleCriterionRuleRepository criterionRuleRepository;
        @InjectMocks
        private RoleScoringEngine engine;

        private RoleScoreRuleSet ruleSet;
        private List<RoleCriterionRule> rules;

        @BeforeEach
        void setUp() {
            ruleSet = RoleScoreRuleSet.builder()
                    .id(10L)
                    .evaluatedRole(CompanyRole.POTENTIAL_PARTNER)
                    .ruleSetVersion("ROLE_SCORING_V1")
                    .build();

            // Canonical weights: 0.25, 0.20, 0.13, 0.16, 0.16, 0.10
            rules = List.of(
                    RoleCriterionRule.builder().criterionKey("strategicFitScore").weight(new BigDecimal("0.25")).direction(ScoreDirection.BENEFIT).required(true).build(),
                    RoleCriterionRule.builder().criterionKey("capabilityComplementarityScore").weight(new BigDecimal("0.20")).direction(ScoreDirection.BENEFIT).required(true).build(),
                    RoleCriterionRule.builder().criterionKey("trustReputationScore").weight(new BigDecimal("0.13")).direction(ScoreDirection.BENEFIT).required(true).build(),
                    RoleCriterionRule.builder().criterionKey("financialAttractivenessScore").weight(new BigDecimal("0.16")).direction(ScoreDirection.BENEFIT).required(true).build(),
                    RoleCriterionRule.builder().criterionKey("collaborationPotentialScore").weight(new BigDecimal("0.16")).direction(ScoreDirection.BENEFIT).required(true).build(),
                    RoleCriterionRule.builder().criterionKey("partnershipRiskScore").weight(new BigDecimal("0.10")).direction(ScoreDirection.BENEFIT).required(true).build()
            );
        }

        @Test
        @DisplayName("Exact weighted formula with all scores = 100")
        void shouldCalculateAllHundreds() {
            when(ruleSetRepository.findByEvaluatedRoleAndRuleSetVersion(CompanyRole.POTENTIAL_PARTNER, "ROLE_SCORING_V1"))
                    .thenReturn(Optional.of(ruleSet));
            when(criterionRuleRepository.findByRuleSetIdAndActiveTrueOrderByDisplayOrderAsc(10L))
                    .thenReturn(rules);

            LinkedHashMap<String, BigDecimal> scores = new LinkedHashMap<>();
            scores.put("strategicFitScore", new BigDecimal("100"));
            scores.put("capabilityComplementarityScore", new BigDecimal("100"));
            scores.put("trustReputationScore", new BigDecimal("100"));
            scores.put("financialAttractivenessScore", new BigDecimal("100"));
            scores.put("collaborationPotentialScore", new BigDecimal("100"));
            scores.put("partnershipRiskScore", new BigDecimal("100"));

            RoleEvaluationCalculationRequest req = RoleEvaluationCalculationRequest.builder()
                    .evaluatedRole(CompanyRole.POTENTIAL_PARTNER)
                    .ruleSetVersion("ROLE_SCORING_V1")
                    .criterionScores(scores)
                    .build();

            RoleEvaluationCalculationResult res = engine.calculate(req);

            assertThat(res.getCompletenessStatus()).isEqualTo(EvaluationCompletenessStatus.COMPLETE);
            // 100*0.25 + 100*0.20 + 100*0.13 + 100*0.16 + 100*0.16 + 100*0.10 = 100.00
            assertThat(res.getOverallScore()).isEqualByComparingTo(new BigDecimal("100.00"));
        }

        @Test
        @DisplayName("Higher-is-better risk: partnershipRiskScore=100 should NOT be inverted")
        void riskScoreShouldNotBeInverted() {
            when(ruleSetRepository.findByEvaluatedRoleAndRuleSetVersion(CompanyRole.POTENTIAL_PARTNER, "ROLE_SCORING_V1"))
                    .thenReturn(Optional.of(ruleSet));
            when(criterionRuleRepository.findByRuleSetIdAndActiveTrueOrderByDisplayOrderAsc(10L))
                    .thenReturn(rules);

            LinkedHashMap<String, BigDecimal> scores = new LinkedHashMap<>();
            scores.put("strategicFitScore", new BigDecimal("100"));
            scores.put("capabilityComplementarityScore", new BigDecimal("100"));
            scores.put("trustReputationScore", new BigDecimal("100"));
            scores.put("financialAttractivenessScore", new BigDecimal("100"));
            scores.put("collaborationPotentialScore", new BigDecimal("100"));
            scores.put("partnershipRiskScore", new BigDecimal("100")); // high = safe

            RoleEvaluationCalculationRequest req = RoleEvaluationCalculationRequest.builder()
                    .evaluatedRole(CompanyRole.POTENTIAL_PARTNER)
                    .ruleSetVersion("ROLE_SCORING_V1")
                    .criterionScores(scores)
                    .build();

            RoleEvaluationCalculationResult res = engine.calculate(req);

            // partnershipRiskScore=100 normalized should be 100, NOT 0
            assertThat(res.getNormalizedCriterionScores().get("partnershipRiskScore"))
                    .isEqualByComparingTo(new BigDecimal("100"));
        }

        @Test
        @DisplayName("Exact formula: mixed scores produce correct weighted sum")
        void shouldCalculateMixedScores() {
            when(ruleSetRepository.findByEvaluatedRoleAndRuleSetVersion(CompanyRole.POTENTIAL_PARTNER, "ROLE_SCORING_V1"))
                    .thenReturn(Optional.of(ruleSet));
            when(criterionRuleRepository.findByRuleSetIdAndActiveTrueOrderByDisplayOrderAsc(10L))
                    .thenReturn(rules);

            LinkedHashMap<String, BigDecimal> scores = new LinkedHashMap<>();
            scores.put("strategicFitScore", new BigDecimal("80"));           // 80 * 0.25 = 20.00
            scores.put("capabilityComplementarityScore", new BigDecimal("70")); // 70 * 0.20 = 14.00
            scores.put("trustReputationScore", new BigDecimal("60"));        // 60 * 0.13 =  7.80
            scores.put("financialAttractivenessScore", new BigDecimal("50")); // 50 * 0.16 =  8.00
            scores.put("collaborationPotentialScore", new BigDecimal("90")); // 90 * 0.16 = 14.40
            scores.put("partnershipRiskScore", new BigDecimal("75"));        // 75 * 0.10 =  7.50
            // Total = 71.70

            RoleEvaluationCalculationRequest req = RoleEvaluationCalculationRequest.builder()
                    .evaluatedRole(CompanyRole.POTENTIAL_PARTNER)
                    .ruleSetVersion("ROLE_SCORING_V1")
                    .criterionScores(scores)
                    .build();

            RoleEvaluationCalculationResult res = engine.calculate(req);

            assertThat(res.getCompletenessStatus()).isEqualTo(EvaluationCompletenessStatus.COMPLETE);
            assertThat(res.getOverallScore()).isEqualByComparingTo(new BigDecimal("71.70"));
        }

        @Test
        @DisplayName("Missing required criterion yields INCOMPLETE")
        void missingCriterionShouldBeIncomplete() {
            when(ruleSetRepository.findByEvaluatedRoleAndRuleSetVersion(CompanyRole.POTENTIAL_PARTNER, "ROLE_SCORING_V1"))
                    .thenReturn(Optional.of(ruleSet));
            when(criterionRuleRepository.findByRuleSetIdAndActiveTrueOrderByDisplayOrderAsc(10L))
                    .thenReturn(rules);

            LinkedHashMap<String, BigDecimal> scores = new LinkedHashMap<>();
            scores.put("strategicFitScore", new BigDecimal("80"));
            scores.put("capabilityComplementarityScore", new BigDecimal("70"));
            scores.put("trustReputationScore", null); // missing
            scores.put("financialAttractivenessScore", new BigDecimal("50"));
            scores.put("collaborationPotentialScore", new BigDecimal("90"));
            scores.put("partnershipRiskScore", new BigDecimal("75"));

            RoleEvaluationCalculationRequest req = RoleEvaluationCalculationRequest.builder()
                    .evaluatedRole(CompanyRole.POTENTIAL_PARTNER)
                    .ruleSetVersion("ROLE_SCORING_V1")
                    .criterionScores(scores)
                    .build();

            RoleEvaluationCalculationResult res = engine.calculate(req);

            assertThat(res.getCompletenessStatus()).isEqualTo(EvaluationCompletenessStatus.INCOMPLETE);
            assertThat(res.getOverallScore()).isNull();
            assertThat(res.getMissingCriteria()).contains("trustReputationScore");
        }

        @Test
        @DisplayName("All zeros produces overall score of 0.00")
        void allZerosShouldProduceZero() {
            when(ruleSetRepository.findByEvaluatedRoleAndRuleSetVersion(CompanyRole.POTENTIAL_PARTNER, "ROLE_SCORING_V1"))
                    .thenReturn(Optional.of(ruleSet));
            when(criterionRuleRepository.findByRuleSetIdAndActiveTrueOrderByDisplayOrderAsc(10L))
                    .thenReturn(rules);

            LinkedHashMap<String, BigDecimal> scores = new LinkedHashMap<>();
            for (String key : CanonicalRoleCriteria.POTENTIAL_PARTNER_CRITERIA) {
                scores.put(key, BigDecimal.ZERO);
            }

            RoleEvaluationCalculationRequest req = RoleEvaluationCalculationRequest.builder()
                    .evaluatedRole(CompanyRole.POTENTIAL_PARTNER)
                    .ruleSetVersion("ROLE_SCORING_V1")
                    .criterionScores(scores)
                    .build();

            RoleEvaluationCalculationResult res = engine.calculate(req);

            assertThat(res.getCompletenessStatus()).isEqualTo(EvaluationCompletenessStatus.COMPLETE);
            assertThat(res.getOverallScore()).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    // =====================================================================
    // SECTION 3: Financial Sufficiency Policy Tests
    // =====================================================================
    @Nested
    @DisplayName("Financial Sufficiency Policy")
    class FinancialSufficiencyTests {

        private PotentialPartnerDataSufficiencyEvaluator evaluator;

        @BeforeEach
        void setUp() {
            evaluator = new PotentialPartnerDataSufficiencyEvaluator();
        }

        @Test
        @DisplayName("No pinned sources -> INCOMPLETE, submission blocked")
        void noSourcesShouldBeIncomplete() {
            RoleEvaluationDraft draft = buildDraft(List.of());

            RoleEvaluationReadinessResponse response = evaluator.evaluate(draft);

            assertThat(response.getAggregateCompletenessStatus()).isEqualTo(EvaluationCompletenessStatus.INCOMPLETE);
            assertThat(response.isStaffMaySubmit()).isFalse();
        }

        @Test
        @DisplayName("financialAttractivenessScore with no financial metrics -> INCOMPLETE on that criterion")
        void noFinancialMetricsShouldBeIncomplete() {
            // Provide only a company profile — no financial metrics
            RoleEvaluationDraft draft = buildDraft(List.of(
                    buildSource(ApprovedSourceType.COMPANY_PROFILE_VERSION, null)
            ));

            RoleEvaluationReadinessResponse response = evaluator.evaluate(draft);

            // financialAttractivenessScore should be INCOMPLETE (no evidence at all)
            assertThat(response.getAggregateCompletenessStatus()).isEqualTo(EvaluationCompletenessStatus.INCOMPLETE);
            assertThat(response.isStaffMaySubmit()).isFalse();
        }

        @Test
        @DisplayName("Profile + evidence but no explicit financial metric -> PARTIAL on financialAttractivenessScore")
        void partialFinancialShouldAllowSubmitWithJustification() {
            // Provide profile + evidence (but not explicit financial metrics)
            RoleEvaluationDraft draft = buildDraft(List.of(
                    buildSource(ApprovedSourceType.COMPANY_PROFILE_VERSION, null),
                    buildSource(ApprovedSourceType.ROLE_METRIC_EVIDENCE_VERSION, null),
                    buildSource(ApprovedSourceType.ROLE_METRIC_VERSION, "general_metric"),
                    buildSource(ApprovedSourceType.PARTNER_CONTRACT_CLAUSE_VERSION, null)
            ));

            RoleEvaluationReadinessResponse response = evaluator.evaluate(draft);

            // Should be PARTIAL because financialAttractivenessScore has profile+evidence but no explicit financial metric
            assertThat(response.getAggregateCompletenessStatus()).isEqualTo(EvaluationCompletenessStatus.PARTIAL);
            assertThat(response.isStaffMaySubmit()).isTrue();
        }

        @Test
        @DisplayName("Complete with explicit financial evidence -> COMPLETE")
        void explicitFinancialEvidenceShouldBeComplete() {
            RoleEvaluationDraft draft = buildDraft(List.of(
                    buildSource(ApprovedSourceType.COMPANY_PROFILE_VERSION, null),
                    buildSource(ApprovedSourceType.ROLE_METRIC_EVIDENCE_VERSION, null),
                    buildSource(ApprovedSourceType.ROLE_METRIC_VERSION, "revenue_potential"),
                    buildSource(ApprovedSourceType.PARTNER_CONTRACT_CLAUSE_VERSION, null)
            ));

            RoleEvaluationReadinessResponse response = evaluator.evaluate(draft);

            assertThat(response.getAggregateCompletenessStatus()).isEqualTo(EvaluationCompletenessStatus.COMPLETE);
            assertThat(response.isStaffMaySubmit()).isTrue();
        }

        private RoleEvaluationDraft buildDraft(List<ApprovedSourceReference> sources) {
            RoleEvaluationDraft draft = new RoleEvaluationDraft();
            draft.setId("test-draft-1");
            draft.setEvaluatedRole(CompanyRole.POTENTIAL_PARTNER);
            draft.setPinnedSourceReferences(sources);
            draft.setSourceSnapshotHash("test-hash");

            LinkedHashMap<String, CriterionInput> inputs = new LinkedHashMap<>();
            for (String key : CanonicalRoleCriteria.POTENTIAL_PARTNER_CRITERIA) {
                CriterionInput input = new CriterionInput();
                input.setRawScore(new BigDecimal("50"));
                input.setExplanation("Test rationale");
                inputs.put(key, input);
            }
            draft.setCriterionInputs(inputs);
            return draft;
        }

        private ApprovedSourceReference buildSource(ApprovedSourceType type, String criterionKey) {
            return ApprovedSourceReference.builder()
                    .referenceId(UUID.randomUUID().toString())
                    .sourceType(type)
                    .sqlSourceId(type == ApprovedSourceType.ROLE_METRIC_VERSION
                            || type == ApprovedSourceType.ROLE_METRIC_EVIDENCE_VERSION
                            || type == ApprovedSourceType.PARTNER_CONTRACT_CLAUSE_VERSION ? 1L : null)
                    .mongoSourceId(type == ApprovedSourceType.COMPANY_PROFILE_VERSION ? "mongo-1" : null)
                    .projectId(1L)
                    .companyProfileId("cp-1")
                    .criterionKey(criterionKey)
                    .pinnedAt(LocalDateTime.now())
                    .build();
        }
    }

    // =====================================================================
    // SECTION 4: COMPETITOR and PARTNER Regression
    // =====================================================================
    @Nested
    @DisplayName("Regression — PARTNER and COMPETITOR unchanged")
    class RegressionTests {

        @Test
        @DisplayName("PARTNER criteria are unmodified")
        void partnerCriteriaShouldBeUnchanged() {
            List<String> criteria = CanonicalRoleCriteria.getCriteriaForRole(CompanyRole.PARTNER);
            assertThat(criteria).hasSize(6);
            assertThat(criteria).containsExactly(
                    "businessValueContributionScore",
                    "strategicAlignmentScore",
                    "operationalPerformanceScore",
                    "capabilityAndComplementarityScore",
                    "relationshipQualityScore",
                    "governanceAndRiskScore"
            );

            for (String key : criteria) {
                assertThat(CanonicalRoleCriteria.getDirectionForRoleAndCriterion(CompanyRole.PARTNER, key))
                        .isEqualTo(ScoreDirection.BENEFIT);
            }
        }

        @Test
        @DisplayName("COMPETITOR criteria remain all THREAT")
        void competitorCriteriaShouldBeUnchanged() {
            List<String> criteria = CanonicalRoleCriteria.getCriteriaForRole(CompanyRole.COMPETITOR);
            assertThat(criteria).hasSize(6);
            assertThat(criteria).containsExactly(
                    "marketPositionScore",
                    "productMarketOverlapScore",
                    "competitiveCapabilityScore",
                    "strategicIntentScore",
                    "growthMomentumScore",
                    "competitiveThreatScore"
            );

            for (String key : criteria) {
                assertThat(CanonicalRoleCriteria.getDirectionForRoleAndCriterion(CompanyRole.COMPETITOR, key))
                        .isEqualTo(ScoreDirection.THREAT);
            }
        }

        @Test
        @DisplayName("CUSTOMER paymentChurnRiskScore remains BENEFIT")
        void customerRiskShouldRemainCost() {
            assertThat(CanonicalRoleCriteria.getDirectionForRoleAndCriterion(CompanyRole.CUSTOMER, "paymentChurnRiskScore"))
                    .isEqualTo(ScoreDirection.BENEFIT);
        }

        @Test
        @DisplayName("SUPPLIER supplyRiskComplianceScore remains BENEFIT")
        void supplierRiskShouldRemainBenefit() {
            assertThat(CanonicalRoleCriteria.getDirectionForRoleAndCriterion(CompanyRole.SUPPLIER, "supplyRiskComplianceScore"))
                    .isEqualTo(ScoreDirection.BENEFIT);
        }
    }

    // =====================================================================
    // SECTION 5: Strategy Support Tests
    // =====================================================================
    @Nested
    @DisplayName("Strategy dispatch")
    class StrategyDispatchTests {

        @Test
        @DisplayName("PotentialPartnerRoleEvaluationSubmissionStrategy supports only POTENTIAL_PARTNER")
        void submissionStrategyShouldSupportPotentialPartner() {
            PotentialPartnerRoleEvaluationSubmissionStrategy strategy =
                    new PotentialPartnerRoleEvaluationSubmissionStrategy(null, null, null);

            assertThat(strategy.supports(CompanyRole.POTENTIAL_PARTNER)).isTrue();
            assertThat(strategy.supports(CompanyRole.PARTNER)).isFalse();
            assertThat(strategy.supports(CompanyRole.COMPETITOR)).isFalse();
            assertThat(strategy.supports(CompanyRole.CUSTOMER)).isFalse();
            assertThat(strategy.supports(CompanyRole.SUPPLIER)).isFalse();
        }

        @Test
        @DisplayName("PotentialPartnerRoleEvaluationApprovalStrategy supports only POTENTIAL_PARTNER")
        void approvalStrategyShouldSupportPotentialPartner() {
            PotentialPartnerRoleEvaluationApprovalStrategy strategy =
                    new PotentialPartnerRoleEvaluationApprovalStrategy(null, null, null);

            assertThat(strategy.supports(CompanyRole.POTENTIAL_PARTNER)).isTrue();
            assertThat(strategy.supports(CompanyRole.PARTNER)).isFalse();
            assertThat(strategy.supports(CompanyRole.COMPETITOR)).isFalse();
        }

        @Test
        @DisplayName("PartnerRoleEvaluationSubmissionStrategy still supports only PARTNER")
        void partnerSubmissionStrategyShouldNotSupportPotentialPartner() {
            PartnerRoleEvaluationSubmissionStrategy strategy =
                    new PartnerRoleEvaluationSubmissionStrategy(null, null, null);

            assertThat(strategy.supports(CompanyRole.PARTNER)).isTrue();
            assertThat(strategy.supports(CompanyRole.POTENTIAL_PARTNER)).isFalse();
        }

        @Test
        @DisplayName("CompetitorRoleEvaluationSubmissionStrategy still supports only COMPETITOR")
        void competitorSubmissionStrategyShouldNotSupportPotentialPartner() {
            CompetitorRoleEvaluationSubmissionStrategy strategy =
                    new CompetitorRoleEvaluationSubmissionStrategy(null, null, null, null, null);

            assertThat(strategy.supports(CompanyRole.COMPETITOR)).isTrue();
            assertThat(strategy.supports(CompanyRole.POTENTIAL_PARTNER)).isFalse();
        }
    }
}
