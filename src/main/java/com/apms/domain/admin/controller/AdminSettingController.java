package com.apms.domain.admin.controller;

import com.apms.common.response.ApiResponse;
import com.apms.domain.admin.dto.AdminSettingsResponse;
import com.apms.domain.admin.service.AdminSettingService;
import com.apms.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/settings")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class AdminSettingController {

    private final AdminSettingService adminSettingService;

    @GetMapping
    public ResponseEntity<ApiResponse<AdminSettingsResponse>> getSettings() {
        return ResponseEntity.ok(ApiResponse.success(adminSettingService.getAllSettings()));
    }

    @PutMapping
    public ResponseEntity<ApiResponse<Void>> saveSettings(
            @RequestBody Map<String, Object> settings,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        adminSettingService.saveSettings(settings, currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success(null, "Settings saved successfully"));
    }
}
