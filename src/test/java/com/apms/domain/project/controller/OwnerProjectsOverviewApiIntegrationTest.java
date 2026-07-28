package com.apms.domain.project.controller;

import com.apms.common.security.ProjectSecurityEvaluator;
import com.apms.config.SecurityConfig;
import com.apms.domain.candidate.controller.CandidateController;
import com.apms.domain.candidate.service.CandidateService;
import com.apms.domain.project.dto.ProjectResponse;
import com.apms.domain.project.service.ProjectService;
import com.apms.security.AuthEntryPointJwt;
import com.apms.security.JwtUtils;
import com.apms.security.UserDetailsImpl;
import com.apms.security.UserDetailsServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({ProjectController.class, CandidateController.class})
@Import(SecurityConfig.class)
@EnableMethodSecurity
class OwnerProjectsOverviewApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProjectService projectService;

    @MockitoBean
    private CandidateService candidateService;

    @MockitoBean(name = "projectSecurity")
    private ProjectSecurityEvaluator projectSecurityEvaluator;

    @MockitoBean
    private JwtUtils jwtUtils;

    @MockitoBean
    private UserDetailsServiceImpl userDetailsService;

    @MockitoBean
    private AuthEntryPointJwt authEntryPointJwt;

    @Test
    @WithMockUser(roles = "BUSINESS_OWNER")
    @DisplayName("Owner GET /api/v1/projects returns system-wide projects list (200 OK)")
    void getAllProjects_asOwner_shouldReturn200() throws Exception {
        ProjectResponse dto = ProjectResponse.builder()
                .id(1L)
                .projectName("System Wide Strategy")
                .targetCompanyName("Acme Corp")
                .build();
        when(projectService.getAllProjects(any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(dto)));

        mockMvc.perform(get("/api/v1/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].id").value(1))
                .andExpect(jsonPath("$.data.content[0].projectName").value("System Wide Strategy"));
    }

    @Test
    @WithMockUser(roles = "BUSINESS_OWNER")
    @DisplayName("Owner GET /api/v1/projects/{id} returns project detail (200 OK)")
    void getProjectById_asOwner_shouldReturn200() throws Exception {
        ProjectResponse dto = ProjectResponse.builder()
                .id(1L)
                .projectName("System Wide Strategy")
                .build();
        when(projectSecurityEvaluator.isProjectReadable(anyLong())).thenReturn(true);
        when(projectService.getProjectById(1L)).thenReturn(dto);

        mockMvc.perform(get("/api/v1/projects/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(1));
    }

    @Test
    @WithMockUser(roles = "BUSINESS_OWNER")
    @DisplayName("Owner GET /api/v1/projects/{id}/members returns project members (200 OK)")
    void getProjectMembers_asOwner_shouldReturn200() throws Exception {
        when(projectSecurityEvaluator.isMemberOrOwner(anyLong())).thenReturn(true);
        when(projectService.getProjectMembers(1L)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/projects/1/members"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @WithMockUser(roles = "BUSINESS_OWNER")
    @DisplayName("Owner GET /api/v1/projects/{id}/candidates returns candidates list (200 OK)")
    void getProjectCandidates_asOwner_shouldReturn200() throws Exception {
        when(projectSecurityEvaluator.isMemberOrOwner(anyLong())).thenReturn(true);
        when(candidateService.getProjectCandidates(any(), any())).thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/projects/1/candidates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @WithMockUser(roles = "BUSINESS_OWNER")
    @DisplayName("Owner POST /api/v1/projects returns 403 Forbidden (Write disabled)")
    void createProject_asOwner_shouldReturn403() throws Exception {
        String body = """
                {
                  "projectName": "Forbidden Project",
                  "projectType": "RESEARCH_NEW_COMPANY",
                  "targetCompanyName": "Acme Target Corp"
                }
                """;

        mockMvc.perform(post("/api/v1/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }
}
