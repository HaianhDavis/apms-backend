package com.apms.domain.dashboard.controller;

import com.apms.config.SecurityConfig;
import com.apms.domain.dashboard.service.DashboardService;
import com.apms.domain.graph.dto.GraphCompanyDto;
import com.apms.domain.score.dto.ScoreSnapshotDto;
import com.apms.security.AuthEntryPointJwt;
import com.apms.security.JwtUtils;
import com.apms.security.UserDetailsServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DashboardController.class)
@Import(SecurityConfig.class)
@EnableMethodSecurity
class OwnerEcosystemApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DashboardService dashboardService;

    @MockitoBean
    private JwtUtils jwtUtils;

    @MockitoBean
    private UserDetailsServiceImpl userDetailsService;

    @MockitoBean
    private AuthEntryPointJwt authEntryPointJwt;

    @Test
    @WithMockUser(roles = "BUSINESS_OWNER")
    @DisplayName("Owner GET /api/v1/dashboard/partners returns 200 OK and company list")
    void getPartners_asOwner_shouldReturn200() throws Exception {
        GraphCompanyDto dto = GraphCompanyDto.builder()
                .companyId("COMP-101")
                .name("Acme Partner Corp")
                .industry("Technology")
                .build();
        when(dashboardService.getPartners()).thenReturn(List.of(dto));

        mockMvc.perform(get("/api/v1/dashboard/partners"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].companyId").value("COMP-101"))
                .andExpect(jsonPath("$.data[0].name").value("Acme Partner Corp"));
    }

    @Test
    @WithMockUser(roles = "BUSINESS_OWNER")
    @DisplayName("Owner GET /api/v1/dashboard/competitors returns 200 OK")
    void getCompetitors_asOwner_shouldReturn200() throws Exception {
        when(dashboardService.getCompetitors()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/dashboard/competitors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @WithMockUser(roles = "BUSINESS_OWNER")
    @DisplayName("Owner GET /api/v1/dashboard/suppliers returns 200 OK")
    void getSuppliers_asOwner_shouldReturn200() throws Exception {
        when(dashboardService.getSuppliers()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/dashboard/suppliers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @WithMockUser(roles = "BUSINESS_OWNER")
    @DisplayName("Owner GET /api/v1/dashboard/potential-partners returns 200 OK")
    void getPotentialPartners_asOwner_shouldReturn200() throws Exception {
        when(dashboardService.getPotentialPartners()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/dashboard/potential-partners"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @WithMockUser(roles = "BUSINESS_OWNER")
    @DisplayName("Owner GET /api/v1/dashboard/recent-scores returns 200 OK and score snapshots")
    void getRecentScores_asOwner_shouldReturn200() throws Exception {
        ScoreSnapshotDto scoreDto = ScoreSnapshotDto.builder()
                .scoreSnapshotId(1L)
                .companyId("COMP-101")
                .totalScore(85)
                .generatedBy("AI Engine")
                .build();
        when(dashboardService.getRecentScores()).thenReturn(List.of(scoreDto));

        mockMvc.perform(get("/api/v1/dashboard/recent-scores"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].totalScore").value(85));
    }

    @Test
    @WithMockUser(roles = "RESEARCH_STAFF")
    @DisplayName("Staff GET /api/v1/dashboard/partners returns 403 Forbidden")
    void getPartners_asStaff_shouldReturn403() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/partners"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "KEY_MEMBER")
    @DisplayName("KeyMember GET /api/v1/dashboard/recent-scores returns 403 Forbidden")
    void getRecentScores_asKeyMember_shouldReturn403() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/recent-scores"))
                .andExpect(status().isForbidden());
    }
}
