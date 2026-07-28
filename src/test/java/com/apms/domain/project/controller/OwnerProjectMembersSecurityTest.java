// Security test for Owner project member access permissions
package com.apms.domain.project.controller;

import com.apms.common.security.ProjectSecurityEvaluator;
import com.apms.config.SecurityConfig;
import com.apms.domain.project.service.ProjectService;
import com.apms.security.AuthEntryPointJwt;
import com.apms.security.JwtUtils;
import com.apms.security.UserDetailsServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProjectController.class)
@Import(SecurityConfig.class)
@EnableMethodSecurity
class OwnerProjectMembersSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProjectService projectService;

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
    @DisplayName("Owner GET /api/v1/projects/{id}/members returns 200 OK")
    void getProjectMembers_asOwner_shouldReturn200() throws Exception {
        when(projectSecurityEvaluator.isMemberOrOwner(anyLong())).thenReturn(true);
        when(projectService.getProjectMembers(1L)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/projects/1/members"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "BUSINESS_OWNER")
    @DisplayName("Owner POST /api/v1/projects/{id}/members returns 403 Forbidden")
    void addProjectMember_asOwner_shouldReturn403() throws Exception {
        String body = """
                {
                  "accountId": 2,
                  "memberRole": "STAFF"
                }
                """;

        mockMvc.perform(post("/api/v1/projects/1/members")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "BUSINESS_OWNER")
    @DisplayName("Owner DELETE /api/v1/projects/{id}/members/{userId} returns 403 Forbidden")
    void removeProjectMember_asOwner_shouldReturn403() throws Exception {
        mockMvc.perform(delete("/api/v1/projects/1/members/2"))
                .andExpect(status().isForbidden());
    }
}
