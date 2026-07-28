package com.apms.domain.dashboard.controller;

import com.apms.domain.dashboard.dto.DashboardSummaryDto;
import com.apms.domain.dashboard.service.DashboardService;
import com.apms.domain.graph.dto.GraphCompanyDto;
import com.apms.domain.score.dto.ScoreSnapshotDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class OwnerEcosystemE2eIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DashboardService dashboardService;

    @Test
    @WithMockUser(roles = "BUSINESS_OWNER")
    @DisplayName("E2E Test: Business Owner fetches ecosystem overview datasets without project restriction")
    void e2e_ownerFetchEcosystemData_shouldSucceed() throws Exception {
        GraphCompanyDto partner = GraphCompanyDto.builder()
                .companyId("PARTNER-01")
                .name("Global Enterprise Partner")
                .industry("Logistics")
                .build();

        ScoreSnapshotDto score = ScoreSnapshotDto.builder()
                .scoreSnapshotId(10L)
                .companyId("PARTNER-01")
                .totalScore(92)
                .generatedBy("AI Assessor")
                .build();

        when(dashboardService.getPartners()).thenReturn(List.of(partner));
        when(dashboardService.getRecentScores()).thenReturn(List.of(score));

        // 1. Fetch partners
        mockMvc.perform(get("/api/v1/dashboard/partners").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].companyId").value("PARTNER-01"));

        // 2. Fetch recent scores widget data
        mockMvc.perform(get("/api/v1/dashboard/recent-scores").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].totalScore").value(92));
    }
}
