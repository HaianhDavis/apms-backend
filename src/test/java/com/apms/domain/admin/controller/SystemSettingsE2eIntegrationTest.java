package com.apms.domain.admin.controller;

import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.admin.dto.SystemSettingsDto;
import com.apms.domain.admin.dto.SystemSettingsResponseDto;
import com.apms.domain.admin.service.AdminUserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class SystemSettingsE2eIntegrationTest {

    @Autowired
    private AdminUserService adminUserService;

    @Test
    @DisplayName("Settings E2E 1: Update system settings persists and can be queried via GET")
    void e2e1_updateAndGetSettings_shouldPersistChanges() {
        SystemSettingsDto dto = SystemSettingsDto.builder()
                .system(Map.of("ai_threshold", "80", "lang", "English"))
                .security(Map.of("mfa", true, "pass_policy", true))
                .trustedIps(List.of("172.16.0.0/12", "192.168.1.50"))
                .build();

        SystemSettingsResponseDto updated = adminUserService.updateSystemSettings(dto);
        assertNotNull(updated);
        assertEquals("80", updated.getSystem().get("ai_threshold"));
        assertEquals("English", updated.getSystem().get("lang"));
        assertTrue(updated.getSecurity().get("mfa"));
        assertTrue(updated.getSecurity().get("pass_policy"));
        assertTrue(updated.getTrustedIps().contains("172.16.0.0/12"));

        // Query back via GET
        SystemSettingsResponseDto fetched = adminUserService.getSystemSettings();
        assertEquals("80", fetched.getSystem().get("ai_threshold"));
        assertTrue(fetched.getTrustedIps().contains("172.16.0.0/12"));
    }

    @Test
    @DisplayName("Settings E2E 2: Update settings with invalid IP throws BusinessValidationException")
    void e2e2_updateSettings_invalidIp_shouldThrowException() {
        SystemSettingsDto dto = SystemSettingsDto.builder()
                .trustedIps(List.of("999.999.999.999"))
                .build();

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () -> {
            adminUserService.updateSystemSettings(dto);
        });

        assertTrue(ex.getMessage().contains("Invalid IP/CIDR format"));
    }

    @Test
    @DisplayName("Settings E2E 3: Update settings with negative AI threshold throws BusinessValidationException")
    void e2e3_updateSettings_negativeThreshold_shouldThrowException() {
        SystemSettingsDto dto = SystemSettingsDto.builder()
                .system(Map.of("ai_threshold", "-10"))
                .build();

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () -> {
            adminUserService.updateSystemSettings(dto);
        });

        assertTrue(ex.getMessage().contains("AI Threshold must be between 0 and 100"));
    }

    @Test
    @DisplayName("Settings E2E 4: SystemSettingsResponseDto contains no unexpected leak fields")
    void e2e4_responseSchema_shouldNotLeakSensitiveFields() {
        SystemSettingsResponseDto res = adminUserService.getSystemSettings();
        assertNotNull(res.getSystem());
        assertNotNull(res.getSecurity());
        assertNotNull(res.getTrustedIps());

        // Ensure no internal fields like password, hash, token exist in map
        assertFalse(res.getSystem().containsKey("password"));
        assertFalse(res.getSecurity().containsKey("secret"));
    }
}
