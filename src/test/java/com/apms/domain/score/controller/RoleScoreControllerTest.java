package com.apms.domain.score.controller;

import com.apms.domain.company.enums.CompanyRole;
import com.apms.domain.profile.service.OwnerOrganizationService;
import com.apms.domain.score.RoleScoreRuleSet;
import com.apms.domain.score.ScoreSnapshot;
import com.apms.domain.score.repository.sql.RoleScoreRuleSetRepository;
import com.apms.domain.score.repository.sql.ScoreSnapshotRepository;
import com.apms.domain.score.service.CanonicalScoreJsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import org.springframework.web.util.NestedServletException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class RoleScoreControllerTest {

    private MockMvc mockMvc;

    @Mock
    private ScoreSnapshotRepository scoreSnapshotRepository;

    @Mock
    private RoleScoreRuleSetRepository ruleSetRepository;

    @Mock
    private CanonicalScoreJsonMapper jsonMapper;

    @Mock
    private OwnerOrganizationService ownerOrganizationService;

    @InjectMocks
    private RoleScoreController controller;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void shouldReturnRoleScores() throws Exception {
        when(ownerOrganizationService.getOwnerCompanyId()).thenReturn("owner");

        ScoreSnapshot mockSnapshot = new ScoreSnapshot();
        mockSnapshot.setScoreSnapshotId(1L);
        mockSnapshot.setEvaluatedRole(CompanyRole.COMPETITOR);
        when(scoreSnapshotRepository.findByTargetCompanyProfileIdAndEvaluatedRoleIsNotNullOrderByCalculatedAtDesc("target"))
                .thenReturn(List.of(mockSnapshot));

        mockMvc.perform(get("/api/v1/profiles/target/role-scores"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].evaluatedRole").value("COMPETITOR"));
    }

    @Test
    void shouldRejectOwnerAsTarget() throws Exception {
        when(ownerOrganizationService.getOwnerCompanyId()).thenReturn("owner");

        assertThatThrownBy(() -> mockMvc.perform(get("/api/v1/profiles/owner/role-scores")))
                .hasCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be evaluated as a target company");
    }

    @Test
    void shouldReturnRuleSets() throws Exception {
        RoleScoreRuleSet mockRuleSet = new RoleScoreRuleSet();
        mockRuleSet.setId(10L);
        mockRuleSet.setEvaluatedRole(CompanyRole.PARTNER);
        mockRuleSet.setActive(true);

        when(ruleSetRepository.findAll()).thenReturn(List.of(mockRuleSet));

        mockMvc.perform(get("/api/v1/role-score-rule-sets?role=PARTNER&active=true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(10))
                .andExpect(jsonPath("$.data[0].evaluatedRole").value("PARTNER"));
    }
}
