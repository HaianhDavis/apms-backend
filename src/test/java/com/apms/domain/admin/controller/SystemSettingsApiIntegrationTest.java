package com.apms.domain.admin.controller;

import com.apms.common.exception.GlobalExceptionHandler;

import com.apms.domain.admin.dto.SystemSettingsDto;
import com.apms.domain.admin.dto.SystemSettingsResponseDto;
import com.apms.domain.admin.service.AdminUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class SystemSettingsApiIntegrationTest {

    private MockMvc mockMvc;

    @Mock
    private AdminUserService adminUserService;

    @InjectMocks
    private AdminController adminController;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(adminController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("GET /api/v1/admin/settings returns 200 OK with system, security, trustedIps")
    void getSettings_shouldReturn200WithSettings() throws Exception {
        SystemSettingsResponseDto mockResponse = SystemSettingsResponseDto.builder()
                .system(Map.of("ai_threshold", "75", "lang", "Vietnamese"))
                .security(Map.of("mfa", true, "audit", true))
                .trustedIps(List.of("192.168.1.0/24"))
                .build();

        when(adminUserService.getSystemSettings()).thenReturn(mockResponse);

        mockMvc.perform(get("/api/v1/admin/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.system.ai_threshold").value("75"))
                .andExpect(jsonPath("$.data.security.mfa").value(true))
                .andExpect(jsonPath("$.data.trustedIps[0]").value("192.168.1.0/24"));
    }

    @Test
    @DisplayName("PUT /api/v1/admin/settings updates settings and returns 200 OK")
    void updateSettings_validPayload_shouldReturn200() throws Exception {
        SystemSettingsResponseDto updatedResponse = SystemSettingsResponseDto.builder()
                .system(Map.of("ai_threshold", "85"))
                .security(Map.of("mfa", false))
                .trustedIps(List.of("10.0.0.1"))
                .build();

        when(adminUserService.updateSystemSettings(any(SystemSettingsDto.class))).thenReturn(updatedResponse);

        String jsonPayload = """
                {
                  "system": { "ai_threshold": "85" },
                  "security": { "mfa": false },
                  "trustedIps": ["10.0.0.1"]
                }
                """;

        mockMvc.perform(put("/api/v1/admin/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.system.ai_threshold").value("85"))
                .andExpect(jsonPath("$.data.security.mfa").value(false));
    }

    @Test
    @DisplayName("PUT /api/v1/admin/settings with invalid AI threshold (>100) returns 400 Bad Request")
    void updateSettings_invalidThreshold_shouldReturn400() throws Exception {
        when(adminUserService.updateSystemSettings(any(SystemSettingsDto.class)))
                .thenThrow(new com.apms.common.exception.BusinessValidationException("AI Threshold must be between 0 and 100"));

        String jsonPayload = """
                {
                  "system": { "ai_threshold": "150" }
                }
                """;

        mockMvc.perform(put("/api/v1/admin/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("AI Threshold must be between 0 and 100"));
    }
}
