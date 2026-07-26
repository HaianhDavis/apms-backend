package com.apms.domain.score.engine;

import com.apms.domain.company.enums.CompanyRole;
import com.apms.domain.score.RoleCriterionRule;
import com.apms.domain.score.RoleScoreRuleSet;
import com.apms.domain.score.dto.RoleEvaluationCalculationRequest;
import com.apms.domain.score.dto.RoleEvaluationCalculationResult;
import com.apms.domain.score.enums.EvaluationCompletenessStatus;
import com.apms.domain.score.enums.ScoreDirection;
import com.apms.domain.score.repository.sql.RoleCriterionRuleRepository;
import com.apms.domain.score.repository.sql.RoleScoreRuleSetRepository;
import org.junit.jupiter.api.BeforeEach;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoleScoringEngineTest {

    @Mock
    private RoleScoreRuleSetRepository ruleSetRepository;
    
    @Mock
    private RoleCriterionRuleRepository criterionRuleRepository;

    @InjectMocks
    private RoleScoringEngine engine;

    private RoleScoreRuleSet partnerRuleSet;
    private List<RoleCriterionRule> partnerRules;
    
    private RoleScoreRuleSet potentialPartnerRuleSet;
    private List<RoleCriterionRule> potentialPartnerRules;

    private List<RoleCriterionRule> validPartnerRules;

    @BeforeEach
    void setUp() {
        partnerRuleSet = RoleScoreRuleSet.builder().id(1L).evaluatedRole(CompanyRole.PARTNER).ruleSetVersion("v1").build();
        
        partnerRules = List.of(
            RoleCriterionRule.builder().criterionKey("businessValueContributionScore").weight(new BigDecimal("0.50")).direction(ScoreDirection.BENEFIT).required(true).build(),
            RoleCriterionRule.builder().criterionKey("strategicAlignmentScore").weight(new BigDecimal("0.50")).direction(ScoreDirection.BENEFIT).required(true).build()
        );
        
        validPartnerRules = List.of(
            RoleCriterionRule.builder().criterionKey("businessValueContributionScore").weight(new BigDecimal("0.20")).direction(ScoreDirection.BENEFIT).required(true).build(),
            RoleCriterionRule.builder().criterionKey("strategicAlignmentScore").weight(new BigDecimal("0.20")).direction(ScoreDirection.BENEFIT).required(true).build(),
            RoleCriterionRule.builder().criterionKey("operationalPerformanceScore").weight(new BigDecimal("0.15")).direction(ScoreDirection.BENEFIT).required(true).build(),
            RoleCriterionRule.builder().criterionKey("capabilityAndComplementarityScore").weight(new BigDecimal("0.15")).direction(ScoreDirection.BENEFIT).required(true).build(),
            RoleCriterionRule.builder().criterionKey("relationshipQualityScore").weight(new BigDecimal("0.15")).direction(ScoreDirection.BENEFIT).required(true).build(),
            RoleCriterionRule.builder().criterionKey("governanceAndRiskScore").weight(new BigDecimal("0.15")).direction(ScoreDirection.BENEFIT).required(true).build()
        );
        
        potentialPartnerRuleSet = RoleScoreRuleSet.builder().id(2L).evaluatedRole(CompanyRole.POTENTIAL_PARTNER).ruleSetVersion("v1").build();
        
        potentialPartnerRules = List.of(
            RoleCriterionRule.builder().criterionKey("strategicFitScore").weight(new BigDecimal("0.25")).direction(ScoreDirection.BENEFIT).required(true).build(),
            RoleCriterionRule.builder().criterionKey("capabilityComplementarityScore").weight(new BigDecimal("0.20")).direction(ScoreDirection.BENEFIT).required(true).build(),
            RoleCriterionRule.builder().criterionKey("trustReputationScore").weight(new BigDecimal("0.13")).direction(ScoreDirection.BENEFIT).required(true).build(),
            RoleCriterionRule.builder().criterionKey("financialAttractivenessScore").weight(new BigDecimal("0.16")).direction(ScoreDirection.BENEFIT).required(true).build(),
            RoleCriterionRule.builder().criterionKey("collaborationPotentialScore").weight(new BigDecimal("0.16")).direction(ScoreDirection.BENEFIT).required(true).build(),
            RoleCriterionRule.builder().criterionKey("partnershipRiskScore").weight(new BigDecimal("0.10")).direction(ScoreDirection.BENEFIT).required(true).build()
        );
    }

    @Test
    void shouldCalculateBenefitScoresCorrectly() {
        when(ruleSetRepository.findByEvaluatedRoleAndRuleSetVersion(CompanyRole.PARTNER, "v1")).thenReturn(Optional.of(partnerRuleSet));
        when(criterionRuleRepository.findByRuleSetIdAndActiveTrueOrderByDisplayOrderAsc(1L)).thenReturn(validPartnerRules);
        
        LinkedHashMap<String, BigDecimal> scores = new LinkedHashMap<>();
        scores.put("businessValueContributionScore", new BigDecimal("80"));
        scores.put("strategicAlignmentScore", new BigDecimal("90"));
        scores.put("operationalPerformanceScore", new BigDecimal("100"));
        scores.put("capabilityAndComplementarityScore", new BigDecimal("100"));
        scores.put("relationshipQualityScore", new BigDecimal("100"));
        scores.put("governanceAndRiskScore", new BigDecimal("100"));

        RoleEvaluationCalculationRequest req = RoleEvaluationCalculationRequest.builder()
                .evaluatedRole(CompanyRole.PARTNER)
                .ruleSetVersion("v1")
                .criterionScores(scores)
                .build();

        RoleEvaluationCalculationResult res = engine.calculate(req);

        assertThat(res.getCompletenessStatus()).isEqualTo(EvaluationCompletenessStatus.COMPLETE);
        // (80*0.2) + (90*0.2) + (100*0.15)*4 = 16 + 18 + 60 = 94.00
        assertThat(res.getOverallScore().compareTo(new BigDecimal("94.00"))).isEqualTo(0);
        assertThat(res.getNormalizedCriterionScores().get("businessValueContributionScore")).isEqualTo(new BigDecimal("80"));
    }

    @Test
    void shouldCalculateAllBenefitScoresForPotentialPartner() {
        when(ruleSetRepository.findByEvaluatedRoleAndRuleSetVersion(CompanyRole.POTENTIAL_PARTNER, "v1")).thenReturn(Optional.of(potentialPartnerRuleSet));
        when(criterionRuleRepository.findByRuleSetIdAndActiveTrueOrderByDisplayOrderAsc(2L)).thenReturn(potentialPartnerRules);
        
        LinkedHashMap<String, BigDecimal> scores = new LinkedHashMap<>();
        scores.put("strategicFitScore", new BigDecimal("100")); // Benefit: 100 * 0.25 = 25
        scores.put("capabilityComplementarityScore", new BigDecimal("100")); // Benefit: 100 * 0.20 = 20
        scores.put("trustReputationScore", new BigDecimal("100")); // Benefit: 100 * 0.13 = 13
        scores.put("financialAttractivenessScore", new BigDecimal("100")); // Benefit: 100 * 0.16 = 16
        scores.put("collaborationPotentialScore", new BigDecimal("100")); // Benefit: 100 * 0.16 = 16
        scores.put("partnershipRiskScore", new BigDecimal("100")); // Benefit: 100 * 0.10 = 10

        RoleEvaluationCalculationRequest req = RoleEvaluationCalculationRequest.builder()
                .evaluatedRole(CompanyRole.POTENTIAL_PARTNER)
                .ruleSetVersion("v1")
                .criterionScores(scores)
                .build();

        RoleEvaluationCalculationResult res = engine.calculate(req);

        assertThat(res.getCompletenessStatus()).isEqualTo(EvaluationCompletenessStatus.COMPLETE);
        assertThat(res.getOverallScore().compareTo(new BigDecimal("100.00"))).isEqualTo(0);
        assertThat(res.getNormalizedCriterionScores().get("partnershipRiskScore").compareTo(new BigDecimal("100"))).isEqualTo(0);
    }
    
    @Test
    void shouldNotInvertBenefitRiskScore() {
        when(ruleSetRepository.findByEvaluatedRoleAndRuleSetVersion(CompanyRole.POTENTIAL_PARTNER, "v1")).thenReturn(Optional.of(potentialPartnerRuleSet));
        when(criterionRuleRepository.findByRuleSetIdAndActiveTrueOrderByDisplayOrderAsc(2L)).thenReturn(potentialPartnerRules);
        
        LinkedHashMap<String, BigDecimal> scores = new LinkedHashMap<>();
        scores.put("strategicFitScore", new BigDecimal("0")); // Benefit: 0 * 0.25 = 0
        scores.put("capabilityComplementarityScore", new BigDecimal("0")); // Benefit: 0 * 0.20 = 0
        scores.put("trustReputationScore", new BigDecimal("0")); // Benefit: 0 * 0.13 = 0
        scores.put("financialAttractivenessScore", new BigDecimal("0")); // Benefit: 0 * 0.16 = 0
        scores.put("collaborationPotentialScore", new BigDecimal("0")); // Benefit: 0 * 0.16 = 0
        scores.put("partnershipRiskScore", new BigDecimal("0")); // Benefit: 0 * 0.10 = 0

        RoleEvaluationCalculationRequest req = RoleEvaluationCalculationRequest.builder()
                .evaluatedRole(CompanyRole.POTENTIAL_PARTNER)
                .ruleSetVersion("v1")
                .criterionScores(scores)
                .build();

        RoleEvaluationCalculationResult res = engine.calculate(req);

        assertThat(res.getCompletenessStatus()).isEqualTo(EvaluationCompletenessStatus.COMPLETE);
        assertThat(res.getOverallScore().compareTo(new BigDecimal("0.00"))).isEqualTo(0);
        // partnershipRiskScore=0 with BENEFIT: normalized stays 0, NOT 100
        assertThat(res.getNormalizedCriterionScores().get("partnershipRiskScore").compareTo(BigDecimal.ZERO)).isEqualTo(0);
    }

    @Test
    void shouldReturnIncompleteIfRequiredMissing() {
        when(ruleSetRepository.findByEvaluatedRoleAndRuleSetVersion(CompanyRole.PARTNER, "v1")).thenReturn(Optional.of(partnerRuleSet));
        when(criterionRuleRepository.findByRuleSetIdAndActiveTrueOrderByDisplayOrderAsc(1L)).thenReturn(validPartnerRules);
        
        LinkedHashMap<String, BigDecimal> scores = new LinkedHashMap<>();
        scores.put("businessValueContributionScore", new BigDecimal("80"));
        scores.put("strategicAlignmentScore", null);

        RoleEvaluationCalculationRequest req = RoleEvaluationCalculationRequest.builder()
                .evaluatedRole(CompanyRole.PARTNER)
                .ruleSetVersion("v1")
                .criterionScores(scores)
                .build();

        RoleEvaluationCalculationResult res = engine.calculate(req);

        assertThat(res.getCompletenessStatus()).isEqualTo(EvaluationCompletenessStatus.INCOMPLETE);
        assertThat(res.getOverallScore()).isNull();
        assertThat(res.getMissingCriteria()).containsExactly(
            "strategicAlignmentScore", 
            "operationalPerformanceScore", 
            "capabilityAndComplementarityScore", 
            "relationshipQualityScore", 
            "governanceAndRiskScore"
        );
        assertThat(res.getNormalizedCriterionScores().get("strategicAlignmentScore")).isNull();
    }
    
    @Test
    void shouldRejectUnknownCriterion() {
        when(ruleSetRepository.findByEvaluatedRoleAndRuleSetVersion(CompanyRole.PARTNER, "v1")).thenReturn(Optional.of(partnerRuleSet));
        when(criterionRuleRepository.findByRuleSetIdAndActiveTrueOrderByDisplayOrderAsc(1L)).thenReturn(validPartnerRules);
        
        LinkedHashMap<String, BigDecimal> scores = new LinkedHashMap<>();
        scores.put("strategicAlignmentScore", new BigDecimal("80"));
        scores.put("operationalPerformanceScore", new BigDecimal("80"));
        scores.put("capabilityAndComplementarityScore", new BigDecimal("80"));
        scores.put("relationshipQualityScore", new BigDecimal("80"));
        scores.put("governanceAndRiskScore", new BigDecimal("80"));
        scores.put("unknownScore", new BigDecimal("50"));

        RoleEvaluationCalculationRequest req = RoleEvaluationCalculationRequest.builder()
                .evaluatedRole(CompanyRole.PARTNER)
                .ruleSetVersion("v1")
                .criterionScores(scores)
                .build();

        assertThatThrownBy(() -> engine.calculate(req))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown criterion");
    }
    
    @Test
    void shouldRejectScoreBelowZero() {
        when(ruleSetRepository.findByEvaluatedRoleAndRuleSetVersion(CompanyRole.PARTNER, "v1")).thenReturn(Optional.of(partnerRuleSet));
        when(criterionRuleRepository.findByRuleSetIdAndActiveTrueOrderByDisplayOrderAsc(1L)).thenReturn(validPartnerRules);
        
        LinkedHashMap<String, BigDecimal> scores = new LinkedHashMap<>();
        scores.put("businessValueContributionScore", new BigDecimal("-1"));
        scores.put("strategicAlignmentScore", new BigDecimal("80"));
        scores.put("operationalPerformanceScore", new BigDecimal("80"));
        scores.put("capabilityAndComplementarityScore", new BigDecimal("80"));
        scores.put("relationshipQualityScore", new BigDecimal("80"));
        scores.put("governanceAndRiskScore", new BigDecimal("80"));

        RoleEvaluationCalculationRequest req = RoleEvaluationCalculationRequest.builder()
                .evaluatedRole(CompanyRole.PARTNER)
                .ruleSetVersion("v1")
                .criterionScores(scores)
                .build();

        assertThatThrownBy(() -> engine.calculate(req))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("between 0 and 100");
    }
    
    @Test
    void shouldRejectMissingRule() {
        when(ruleSetRepository.findByEvaluatedRoleAndRuleSetVersion(CompanyRole.PARTNER, "v1")).thenReturn(Optional.of(partnerRuleSet));
        when(criterionRuleRepository.findByRuleSetIdAndActiveTrueOrderByDisplayOrderAsc(1L)).thenReturn(partnerRules); // partnerRules only has 2

        RoleEvaluationCalculationRequest req = RoleEvaluationCalculationRequest.builder()
                .evaluatedRole(CompanyRole.PARTNER)
                .ruleSetVersion("v1")
                .build();

        assertThatThrownBy(() -> engine.calculate(req))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Exactly six active criterion rules must exist");
    }

    @Test
    void shouldRejectInvalidTotalWeight() {
        when(ruleSetRepository.findByEvaluatedRoleAndRuleSetVersion(CompanyRole.PARTNER, "v1")).thenReturn(Optional.of(partnerRuleSet));
        List<RoleCriterionRule> invalidRules = List.of(
            RoleCriterionRule.builder().criterionKey("businessValueContributionScore").weight(new BigDecimal("0.50")).direction(ScoreDirection.BENEFIT).build(),
            RoleCriterionRule.builder().criterionKey("strategicAlignmentScore").weight(new BigDecimal("0.50")).direction(ScoreDirection.BENEFIT).build(),
            RoleCriterionRule.builder().criterionKey("operationalPerformanceScore").weight(new BigDecimal("0.50")).direction(ScoreDirection.BENEFIT).build(),
            RoleCriterionRule.builder().criterionKey("capabilityAndComplementarityScore").weight(new BigDecimal("0.50")).direction(ScoreDirection.BENEFIT).build(),
            RoleCriterionRule.builder().criterionKey("relationshipQualityScore").weight(new BigDecimal("0.50")).direction(ScoreDirection.BENEFIT).build(),
            RoleCriterionRule.builder().criterionKey("governanceAndRiskScore").weight(new BigDecimal("0.50")).direction(ScoreDirection.BENEFIT).build()
        );
        when(criterionRuleRepository.findByRuleSetIdAndActiveTrueOrderByDisplayOrderAsc(1L)).thenReturn(invalidRules);

        RoleEvaluationCalculationRequest req = RoleEvaluationCalculationRequest.builder()
                .evaluatedRole(CompanyRole.PARTNER)
                .ruleSetVersion("v1")
                .build();

        assertThatThrownBy(() -> engine.calculate(req))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Criterion weights must sum to 1.00");
    }
}
