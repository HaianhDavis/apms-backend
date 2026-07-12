package com.apms.domain.score.service;

import com.apms.domain.company.enums.CompanyRole;
import com.apms.domain.score.RoleScoreRuleSet;
import com.apms.domain.score.enums.WeightSource;
import com.apms.domain.score.enums.WeightingMethod;
import com.apms.domain.score.repository.sql.RoleScoreRuleSetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RoleScoringSeedServiceTest {

    @Mock
    private RoleScoreRuleSetRepository ruleSetRepository;

    @Captor
    private ArgumentCaptor<RoleScoreRuleSet> ruleSetCaptor;

    private RoleScoringSeedService seedService;

    @BeforeEach
    void setUp() {
        seedService = new RoleScoringSeedService(ruleSetRepository);
        ReflectionTestUtils.setField(seedService, "seedIllustrativeRules", true);
    }

    @Test
    void shouldSeedFiveRuleSetsWithSixRulesEachAndWeightsSumToOne() {
        when(ruleSetRepository.existsByEvaluatedRoleAndRuleSetVersion(any(), any())).thenReturn(false);

        seedService.seedRules();

        verify(ruleSetRepository, times(5)).save(ruleSetCaptor.capture());
        List<RoleScoreRuleSet> savedRuleSets = ruleSetCaptor.getAllValues();

        assertThat(savedRuleSets).hasSize(5);

        for (RoleScoreRuleSet ruleSet : savedRuleSets) {
            assertThat(ruleSet.getRuleSetVersion()).isEqualTo("ROLE_SCORING_V1");
            assertThat(ruleSet.getWeightingMethod()).isEqualTo(WeightingMethod.AHP);
            assertThat(ruleSet.getWeightSource()).isEqualTo(WeightSource.ILLUSTRATIVE);
            assertThat(ruleSet.getWeightVersion()).isEqualTo("ILLUSTRATIVE_AHP_V1");
            assertThat(ruleSet.getActive()).isTrue();

            assertThat(ruleSet.getRules()).hasSize(6);

            BigDecimal sum = ruleSet.getRules().stream()
                    .map(r -> r.getWeight())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(sum.compareTo(new BigDecimal("1.00"))).isEqualTo(0);
        }
    }

    @Test
    void shouldBeIdempotentAndNotOverwriteExisting() {
        when(ruleSetRepository.existsByEvaluatedRoleAndRuleSetVersion(CompanyRole.PARTNER, "ROLE_SCORING_V1")).thenReturn(true);
        when(ruleSetRepository.existsByEvaluatedRoleAndRuleSetVersion(CompanyRole.POTENTIAL_PARTNER, "ROLE_SCORING_V1")).thenReturn(true);
        when(ruleSetRepository.existsByEvaluatedRoleAndRuleSetVersion(CompanyRole.COMPETITOR, "ROLE_SCORING_V1")).thenReturn(true);
        when(ruleSetRepository.existsByEvaluatedRoleAndRuleSetVersion(CompanyRole.CUSTOMER, "ROLE_SCORING_V1")).thenReturn(true);
        when(ruleSetRepository.existsByEvaluatedRoleAndRuleSetVersion(CompanyRole.SUPPLIER, "ROLE_SCORING_V1")).thenReturn(true);

        seedService.seedRules();

        verify(ruleSetRepository, never()).save(any());
    }

    @Test
    void shouldNotSeedIfDisabled() {
        ReflectionTestUtils.setField(seedService, "seedIllustrativeRules", false);

        seedService.seedRules();

        verify(ruleSetRepository, never()).save(any());
        verify(ruleSetRepository, never()).existsByEvaluatedRoleAndRuleSetVersion(any(), any());
    }
}
