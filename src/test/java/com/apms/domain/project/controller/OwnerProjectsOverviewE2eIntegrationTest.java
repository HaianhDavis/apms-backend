package com.apms.domain.project.controller;

import com.apms.common.enums.MemberRole;
import com.apms.common.enums.ProjectStatus;
import com.apms.common.enums.ProjectType;
import com.apms.domain.project.dto.ProjectMemberResponse;
import com.apms.domain.project.dto.ProjectResponse;
import com.apms.domain.project.service.ProjectService;
import com.apms.security.UserDetailsImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class OwnerProjectsOverviewE2eIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProjectService projectService;

    @Test
    @WithMockUser(roles = "BUSINESS_OWNER")
    @DisplayName("E2E Test: Owner browses all system projects, inspects details and members without membership restriction")
    void e2e_ownerInspectProjectsAndMembers_shouldSucceed() throws Exception {
        ProjectResponse projectDto = ProjectResponse.builder()
                .id(101L)
                .projectName("E2E Enterprise Partnering")
                .projectType(ProjectType.RESEARCH_NEW_COMPANY)
                .status(ProjectStatus.ACTIVE)
                .targetCompanyName("Target Enterprise Inc")
                .build();

        ProjectMemberResponse memberDto = ProjectMemberResponse.builder()
                .id(1L)
                .accountId(5L)
                .memberRole(MemberRole.STAFF)
                .build();

        when(projectService.getAllProjects(any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(projectDto)));
        when(projectService.getProjectById(101L)).thenReturn(projectDto);
        when(projectService.getProjectMembers(101L)).thenReturn(List.of(memberDto));

        // 1. Fetch system-wide projects
        mockMvc.perform(get("/api/v1/projects").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].id").value(101));

        // 2. Fetch project detail
        mockMvc.perform(get("/api/v1/projects/101").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.projectName").value("E2E Enterprise Partnering"));

        // 3. Fetch project members (Owner role enabled)
        mockMvc.perform(get("/api/v1/projects/101/members").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].accountId").value(5));
    }
}
