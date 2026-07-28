package com.apms.domain.audit.controller;

import com.apms.config.SecurityConfig;
import com.apms.domain.audit.dto.AuditLogResponse;
import com.apms.domain.audit.service.AuditLogQueryService;
import com.apms.security.AuthEntryPointJwt;
import com.apms.security.JwtUtils;
import com.apms.security.UserDetailsServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuditLogController.class)
@Import(SecurityConfig.class)
@EnableMethodSecurity
class AuditLogSecurityApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuditLogQueryService auditLogQueryService;

    @MockitoBean
    private JwtUtils jwtUtils;

    @MockitoBean
    private UserDetailsServiceImpl userDetailsService;

    @MockitoBean
    private AuthEntryPointJwt authEntryPointJwt;

    @Test
    @WithMockUser(roles = "SYSTEM_ADMIN")
    @DisplayName("GET /api/v1/audit-logs by SYSTEM_ADMIN returns 200 OK")
    void getAuditLogs_asSystemAdmin_shouldReturn200() throws Exception {
        AuditLogResponse item = AuditLogResponse.builder().id(1L).action("LOGIN").createdAt(LocalDateTime.now()).build();
        when(auditLogQueryService.searchAuditLogs(any(), any(), any(), any(), any(), any(), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(item)));

        mockMvc.perform(get("/api/v1/audit-logs"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "BUSINESS_DEVELOPMENT_MANAGER")
    @DisplayName("GET /api/v1/audit-logs by MANAGER returns 403 Forbidden")
    void getAuditLogs_asManager_shouldReturn403() throws Exception {
        mockMvc.perform(get("/api/v1/audit-logs"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "BUSINESS_DEVELOPMENT_STAFF")
    @DisplayName("GET /api/v1/audit-logs by STAFF returns 403 Forbidden")
    void getAuditLogs_asStaff_shouldReturn403() throws Exception {
        mockMvc.perform(get("/api/v1/audit-logs"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "BUSINESS_DEVELOPMENT_MANAGER")
    @DisplayName("GET /api/v1/audit-logs/export by MANAGER returns 403 Forbidden")
    void exportAuditLogs_asManager_shouldReturn403() throws Exception {
        mockMvc.perform(get("/api/v1/audit-logs/export"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "BUSINESS_DEVELOPMENT_STAFF")
    @DisplayName("GET /api/v1/audit-logs/export by STAFF returns 403 Forbidden")
    void exportAuditLogs_asStaff_shouldReturn403() throws Exception {
        mockMvc.perform(get("/api/v1/audit-logs/export"))
                .andExpect(status().isForbidden());
    }
}
