package com.apms.domain.director;

import com.apms.config.SecurityConfig;
import com.apms.common.security.ProjectSecurityEvaluator;
import com.apms.domain.admin.controller.AdminController;
import com.apms.domain.admin.service.AdminUserService;
import com.apms.domain.dashboard.controller.DashboardController;
import com.apms.domain.dashboard.service.DashboardService;
import com.apms.domain.graph.controller.GraphController;
import com.apms.domain.graph.service.GraphService;
import com.apms.domain.insight.controller.InsightsController;
import com.apms.domain.insight.service.InsightsService;
import com.apms.domain.project.controller.ProjectController;
import com.apms.domain.project.service.ProjectService;
import com.apms.domain.profile.controller.ProfileController;
import com.apms.domain.profile.service.ProfileService;
import com.apms.domain.score.controller.ScoreController;
import com.apms.domain.score.service.ScoreService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({
        DashboardController.class,
        ProjectController.class,
        ProfileController.class,
        ScoreController.class,
        InsightsController.class,
        GraphController.class,
        AdminController.class
})
@Import(SecurityConfig.class)
@EnableMethodSecurity
@DisplayName("Director API Permissions Integration Tests")
class DirectorApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    // ─────────────────────────────────────────────
    // Mocking all required services & security beans
    // ─────────────────────────────────────────────
    @MockitoBean private DashboardService dashboardService;
    @MockitoBean private ProjectService projectService;
    @MockitoBean private ProfileService profileService;
    @MockitoBean private ScoreService scoreService;
    @MockitoBean private InsightsService insightsService;
    @MockitoBean private GraphService graphService;
    @MockitoBean private AdminUserService adminUserService;

    @MockitoBean private JwtUtils jwtUtils;
    @MockitoBean private UserDetailsServiceImpl userDetailsService;
    @MockitoBean private AuthEntryPointJwt authEntryPointJwt;

    @MockitoBean(name = "projectSecurity")
    private ProjectSecurityEvaluator projectSecurityEvaluator;

    @org.junit.jupiter.api.BeforeEach
    void setUpMocks() {
        // Prevent NullPointerException in PageResponse.of() when mocks return null
        when(profileService.searchCompanyProfiles(any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());
        when(profileService.searchProfilesByName(any(), org.mockito.ArgumentMatchers.anyBoolean(), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());
        when(projectService.getAllProjects(any(), any(), any(), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());
        when(adminUserService.getAuditLogs(any()))
                .thenReturn(org.springframework.data.domain.Page.empty());
    }

    // ─────────────────────────────────────────────
    // NHÓM 1 — Dashboard & Ecosystem
    // ─────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "BUSINESS_DIRECTOR")
    @DisplayName("Director: GET /dashboard/summary → 200")
    void directorGetDashboardSummary() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/summary"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "BUSINESS_DIRECTOR")
    @DisplayName("Director: GET /dashboard/partners → 200")
    void directorGetDashboardPartners() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/partners"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "BUSINESS_DIRECTOR")
    @DisplayName("Director: GET /dashboard/competitors → 200")
    void directorGetDashboardCompetitors() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/competitors"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "BUSINESS_DIRECTOR")
    @DisplayName("Director: GET /dashboard/suppliers → 200")
    void directorGetDashboardSuppliers() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/suppliers"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "BUSINESS_DIRECTOR")
    @DisplayName("Director: GET /dashboard/potential-partners → 200")
    void directorGetDashboardPotentialPartners() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/potential-partners"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "BUSINESS_DIRECTOR")
    @DisplayName("Director: GET /dashboard/recent-scores → 200")
    void directorGetDashboardRecentScores() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/recent-scores"))
                .andExpect(status().isOk());
    }

    // ─────────────────────────────────────────────
    // NHÓM 1 — Projects
    // ─────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "BUSINESS_DIRECTOR")
    @DisplayName("Director: GET /projects → 200")
    void directorGetProjects() throws Exception {
        mockMvc.perform(get("/api/v1/projects"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "BUSINESS_DIRECTOR")
    @DisplayName("Director: GET /projects/{id} → 200")
    void directorGetProjectById() throws Exception {
        when(projectSecurityEvaluator.isProjectReadable(1L)).thenReturn(true);

        mockMvc.perform(get("/api/v1/projects/1"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "BUSINESS_DIRECTOR")
    @DisplayName("Director: GET /projects/{id}/members → 200")
    void directorGetProjectMembers() throws Exception {
        when(projectSecurityEvaluator.isMemberOrOwner(1L)).thenReturn(true);

        mockMvc.perform(get("/api/v1/projects/1/members"))
                .andExpect(status().isOk());
    }

    // ─────────────────────────────────────────────
    // NHÓM 1 — Company Profiles
    // ─────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "BUSINESS_DIRECTOR")
    @DisplayName("Director: GET /company-profiles → 200")
    void directorGetCompanyProfiles() throws Exception {
        mockMvc.perform(get("/api/v1/company-profiles"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "BUSINESS_DIRECTOR")
    @DisplayName("Director: GET /profiles/search?name=a → 200")
    void directorSearchProfiles() throws Exception {
        mockMvc.perform(get("/api/v1/profiles/search?name=a"))
                .andExpect(status().isOk());
    }

    // ─────────────────────────────────────────────
    // NHÓM 1 — Score
    // ─────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "BUSINESS_DIRECTOR")
    @DisplayName("Director: GET /score-rules → 200")
    void directorGetScoreRules() throws Exception {
        mockMvc.perform(get("/api/v1/score-rules"))
                .andExpect(status().isOk());
    }

    // ─────────────────────────────────────────────
    // NHÓM 1 — Audit Logs
    // ─────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "BUSINESS_DIRECTOR")
    @DisplayName("Director: GET /admin/audit-logs → 200")
    void directorGetAuditLogs() throws Exception {
        mockMvc.perform(get("/api/v1/admin/audit-logs"))
                .andExpect(status().isOk());
    }

    // ─────────────────────────────────────────────
    // NHÓM 2 — Insights
    // ─────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "BUSINESS_DIRECTOR")
    @DisplayName("Director: GET /risk-monitoring → 200")
    void directorGetRiskMonitoring() throws Exception {
        mockMvc.perform(get("/api/v1/risk-monitoring"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "BUSINESS_DIRECTOR")
    @DisplayName("Director: GET /kpi/team → 200")
    void directorGetKpiTeam() throws Exception {
        mockMvc.perform(get("/api/v1/kpi/team"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "BUSINESS_DIRECTOR")
    @DisplayName("Director: GET /reports → 200")
    void directorGetReports() throws Exception {
        mockMvc.perform(get("/api/v1/reports"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "BUSINESS_DIRECTOR")
    @DisplayName("Director: GET /analysis/history → 200")
    void directorGetAnalysisHistory() throws Exception {
        mockMvc.perform(get("/api/v1/analysis/history"))
                .andExpect(status().isOk());
    }

    // ─────────────────────────────────────────────
    // NHÓM 2 — Graph
    // ─────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "BUSINESS_DIRECTOR")
    @DisplayName("Director: GET /graph/network → 200")
    void directorGetGraphNetwork() throws Exception {
        mockMvc.perform(get("/api/v1/graph/network"))
                .andExpect(status().isOk());
    }

    // ─────────────────────────────────────────────
    // Regression & Restrictions
    // ─────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "BUSINESS_DEVELOPMENT_MANAGER")
    @DisplayName("Regression: Manager still gets GET /dashboard/summary → 200")
    void managerStillGetsDashboardSummary() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/summary"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "BUSINESS_DIRECTOR")
    @DisplayName("Director: GET /admin/settings → 403 (restricted)")
    void directorCannotGetSystemSettings() throws Exception {
        mockMvc.perform(get("/api/v1/admin/settings"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "BUSINESS_DIRECTOR")
    @DisplayName("Director: GET /accounts → 403 (restricted)")
    void directorCannotGetAccounts() throws Exception {
        mockMvc.perform(get("/api/v1/accounts"))
                .andExpect(status().isForbidden());
    }

    // ─────────────────────────────────────────────
    // Non-Director Roles Regression Checks (Staff, KeyMember, Manager)
    // ─────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "RESEARCH_STAFF")
    @DisplayName("Regression: Staff GET /admin/audit-logs → 403 Forbidden")
    void staffCannotGetAuditLogs() throws Exception {
        mockMvc.perform(get("/api/v1/admin/audit-logs"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "KEY_MEMBER")
    @DisplayName("Regression: Key Member GET /risk-monitoring → 403 Forbidden")
    void keyMemberCannotGetRiskMonitoring() throws Exception {
        mockMvc.perform(get("/api/v1/risk-monitoring"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "RESEARCH_STAFF")
    @DisplayName("Regression: Staff GET /score-rules → 403 Forbidden")
    void staffCannotGetScoreRules() throws Exception {
        mockMvc.perform(get("/api/v1/score-rules"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "BUSINESS_DEVELOPMENT_MANAGER")
    @DisplayName("Regression: Manager GET /admin/audit-logs → 403 Forbidden")
    void managerCannotGetAuditLogs() throws Exception {
        mockMvc.perform(get("/api/v1/admin/audit-logs"))
                .andExpect(status().isForbidden());
    }
}
