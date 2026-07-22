package com.apms.domain.score.service;

import com.apms.domain.company.enums.CompanyRole;
import com.apms.domain.score.RoleScoreRuleSet;
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
}
