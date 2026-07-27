package com.apms.domain.rolemetric.controller;

import com.apms.common.enums.SystemRole;
import com.apms.domain.project.repository.sql.ProjectRepository;
import com.apms.domain.rolemetric.dto.CreateRoleMetricRequest;
import com.apms.domain.rolemetric.dto.ReviewRoleMetricRequest;
import com.apms.domain.rolemetric.service.RoleMetricRecordService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.apms.config.SecurityConfig;
import com.apms.security.AuthEntryPointJwt;
import com.apms.security.AuthTokenFilter;
import com.apms.security.JwtUtils;
import com.apms.security.UserDetailsServiceImpl;
import com.apms.common.security.ProjectSecurityEvaluator;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.apms.ApmsIntegrationTestBase;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;

@WebMvcTest(RoleMetricRecordController.class)
@Import(SecurityConfig.class)
@AutoConfigureMockMvc(addFilters = true)
@EnableMethodSecurity
class RoleMetricRecordSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RoleMetricRecordController controller;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private RoleMetricRecordService roleMetricRecordService;



    @MockitoBean(name = "projectSecurity")
    private ProjectSecurityEvaluator projectSecurity;

    @MockitoBean
    private UserDetailsServiceImpl userDetailsService;

    @MockitoBean
    private AuthEntryPointJwt unauthorizedHandler;

    @MockitoBean
    private AuthTokenFilter authTokenFilter;

    @MockitoBean
    private JwtUtils jwtUtils;

    private ProjectRepository projectRepository;

    @MockitoBean
    private com.apms.domain.rolemetric.repository.RoleMetricRecordRepository recordRepository;

    @MockitoBean
    private com.apms.domain.rolemetric.repository.RoleMetricRecordVersionRepository versionRepository;

    @MockitoBean
    private com.apms.domain.rolemetric.repository.RoleMetricEvidenceVersionRepository evidenceVersionRepository;

    @BeforeEach
    void setup() throws Exception {
        org.mockito.Mockito.doAnswer(invocation -> {
            jakarta.servlet.FilterChain chain = invocation.getArgument(2);
            chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(authTokenFilter).doFilter(any(), any(), any());

        org.mockito.Mockito.doAnswer(invocation -> {
            jakarta.servlet.http.HttpServletResponse response = invocation.getArgument(1);
            response.sendError(jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED, "Error: Unauthorized");
            return null;
        }).when(unauthorizedHandler).commence(any(), any(), any());
    }

    @Test
    @WithMockUser(username = "owner", authorities = {"ROLE_BUSINESS_OWNER"})
    void owner_canReadApproved_butCannotMutateWorking() throws Exception {
        when(projectSecurity.isProjectReadable(any())).thenReturn(true);
        when(projectSecurity.isStaff(any())).thenReturn(false);

        mockMvc.perform(get("/api/v1/projects/1/role-metrics/approved"))
                .andExpect(status().isOk());

        CreateRoleMetricRequest req = new CreateRoleMetricRequest();
        req.setMetricKey("revenue");
        mockMvc.perform(post("/api/v1/projects/1/role-metrics")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin", authorities = {"ROLE_SYSTEM_ADMIN"})
    void admin_cannotReadOrMutate() throws Exception {
        when(projectSecurity.isProjectReadable(any())).thenReturn(false);
        when(projectSecurity.isStaff(any())).thenReturn(false);
        when(projectSecurity.isStaffOrManager(any())).thenReturn(false);

        System.out.println("DEBUG AOP: " + org.springframework.aop.support.AopUtils.isAopProxy(controller));
        mockMvc.perform(get("/api/v1/projects/1/role-metrics/approved"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/projects/1/role-metrics/1/submit"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "manager", authorities = {"ROLE_BUSINESS_DEVELOPMENT_MANAGER"})
    void manager_canReviewSubmitted() throws Exception {
        when(projectSecurity.isManager(any())).thenReturn(true);

        ReviewRoleMetricRequest req = new ReviewRoleMetricRequest();
        req.setDecision(com.apms.domain.rolemetric.enums.RoleMetricReviewDecision.APPROVE);
        mockMvc.perform(post("/api/v1/projects/1/role-metrics/1/review")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "staff", authorities = {"ROLE_BUSINESS_DEVELOPMENT_STAFF"})
    void staff_canMutateAndSubmit() throws Exception {
        when(projectSecurity.isStaff(any())).thenReturn(true);

        CreateRoleMetricRequest req = new CreateRoleMetricRequest();
        req.setMetricKey("revenue");
        req.setPeriodStart(LocalDate.of(2025, 1, 1));
        req.setPeriodEnd(LocalDate.of(2025, 12, 31));
        req.setTargetNumericValue(new BigDecimal("1000"));

        mockMvc.perform(post("/api/v1/projects/1/role-metrics")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/projects/1/role-metrics/1/submit")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isOk());
    }
}
