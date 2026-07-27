package com.apms.domain.score.service;

import com.apms.domain.company.enums.CompanyRole;
import com.apms.domain.score.RoleCriterionRule;
import com.apms.domain.score.RoleScoreRuleSet;
import com.apms.domain.score.enums.ScoreDirection;
import com.apms.domain.score.enums.WeightSource;
import com.apms.domain.score.repository.sql.RoleScoreRuleSetRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class RoleScoringSeedCompatibilityTest {

    @Mock
    private RoleScoreRuleSetRepository ruleSetRepository;

    @InjectMocks
    private RoleScoringSeedService seedService;

    @Test
    void shouldSkipSeedingWhenLegacyRuleSetExists() {
        ReflectionTestUtils.setField(seedService, "seedIllustrativeRules", true);

        // Mock that the rule set already exists (e.g. created previously with legacy keys)
        when(ruleSetRepository.existsByEvaluatedRoleAndRuleSetVersion(eq(CompanyRole.PARTNER), anyString())).thenReturn(true);
        when(ruleSetRepository.existsByEvaluatedRoleAndRuleSetVersion(eq(CompanyRole.POTENTIAL_PARTNER), anyString())).thenReturn(true);
        when(ruleSetRepository.existsByEvaluatedRoleAndRuleSetVersion(eq(CompanyRole.COMPETITOR), anyString())).thenReturn(true);
        when(ruleSetRepository.existsByEvaluatedRoleAndRuleSetVersion(eq(CompanyRole.CUSTOMER), anyString())).thenReturn(true);
        when(ruleSetRepository.existsByEvaluatedRoleAndRuleSetVersion(eq(CompanyRole.SUPPLIER), anyString())).thenReturn(true);

        seedService.seedRules();

        // Ensure we do not save a new rule set if one exists (preventing duplicate canonical rules)
        verify(ruleSetRepository, never()).save(any(RoleScoreRuleSet.class));
    }

    @Test
    void shouldMigrateLegacyPotentialPartnerIllustrativeRules() {
        ReflectionTestUtils.setField(seedService, "seedIllustrativeRules", true);

        when(ruleSetRepository.existsByEvaluatedRoleAndRuleSetVersion(any(CompanyRole.class), anyString())).thenReturn(true);

        RoleScoreRuleSet legacyRuleSet = RoleScoreRuleSet.builder()
                .evaluatedRole(CompanyRole.POTENTIAL_PARTNER)
                .ruleSetVersion("ROLE_SCORING_V1")
                .weightSource(WeightSource.ILLUSTRATIVE)
                .build();
        legacyRuleSet.getRules().add(RoleCriterionRule.builder()
                .criterionKey("partnershipRiskScore")
                .weight(new java.math.BigDecimal("0.11"))
                .direction(ScoreDirection.COST) // Legacy wrong direction
                .build());

        when(ruleSetRepository.findByEvaluatedRoleAndRuleSetVersion(CompanyRole.POTENTIAL_PARTNER, "ROLE_SCORING_V1"))
                .thenReturn(java.util.Optional.of(legacyRuleSet));

        seedService.seedRules();

        verify(ruleSetRepository).save(legacyRuleSet);
        org.assertj.core.api.Assertions.assertThat(legacyRuleSet.getRules().get(0).getWeight()).isEqualByComparingTo(new java.math.BigDecimal("0.10"));
        org.assertj.core.api.Assertions.assertThat(legacyRuleSet.getRules().get(0).getDirection()).isEqualTo(ScoreDirection.BENEFIT);
    }

    @Test
    void shouldNotMigrateExpertPotentialPartnerRules() {
        ReflectionTestUtils.setField(seedService, "seedIllustrativeRules", true);

        when(ruleSetRepository.existsByEvaluatedRoleAndRuleSetVersion(any(CompanyRole.class), anyString())).thenReturn(true);

        RoleScoreRuleSet expertRuleSet = RoleScoreRuleSet.builder()
                .evaluatedRole(CompanyRole.POTENTIAL_PARTNER)
                .ruleSetVersion("ROLE_SCORING_V1")
                .weightSource(WeightSource.EXPERT)
                .build();
        expertRuleSet.getRules().add(RoleCriterionRule.builder()
                .criterionKey("partnershipRiskScore")
                .weight(new java.math.BigDecimal("0.50"))
                .direction(ScoreDirection.COST)
                .build());

        when(ruleSetRepository.findByEvaluatedRoleAndRuleSetVersion(CompanyRole.POTENTIAL_PARTNER, "ROLE_SCORING_V1"))
                .thenReturn(java.util.Optional.of(expertRuleSet));

        seedService.seedRules();

        // Should not save the expert rule set, should skip it
        verify(ruleSetRepository, never()).save(expertRuleSet);
        org.assertj.core.api.Assertions.assertThat(expertRuleSet.getRules().get(0).getWeight()).isEqualByComparingTo(new java.math.BigDecimal("0.50"));
    }
}
