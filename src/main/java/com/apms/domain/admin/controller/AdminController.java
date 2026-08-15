package com.apms.domain.admin.controller;

import com.apms.common.response.ApiResponse;
import com.apms.domain.admin.dto.*;
import com.apms.domain.admin.service.AdminSettingsService;
import com.apms.domain.admin.service.SystemHealthService;
import com.apms.security.UserDetailsImpl;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class AdminController {

    private final AdminSettingsService adminSettingsService;
    private final SystemHealthService systemHealthService;

    @GetMapping("/settings")
    public ResponseEntity<ApiResponse<SystemSettingsResponse>> getSettings() {
        return ResponseEntity.ok(ApiResponse.success(adminSettingsService.getSettings()));
    }

    @PutMapping("/settings")
    public ResponseEntity<ApiResponse<Void>> updateSettings(
            @RequestBody Map<String, String> settings,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        adminSettingsService.updateSettings(settings, currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success(null, "Settings updated"));
    }

    @GetMapping("/security/ip-whitelist")
    public ResponseEntity<ApiResponse<IpWhitelistResponse>> getIpWhitelist() {
        return ResponseEntity.ok(ApiResponse.success(adminSettingsService.getIpWhitelist()));
    }

    @PatchMapping("/security/ip-whitelist/status")
    public ResponseEntity<ApiResponse<Void>> updateIpWhitelistStatus(
            @Valid @RequestBody UpdateIpWhitelistStatusRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        adminSettingsService.setIpWhitelistEnabled(request.getEnabled(), currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success(null, "IP whitelist status updated"));
    }

    @PostMapping("/security/ip-whitelist")
    public ResponseEntity<ApiResponse<IpWhitelistEntryDto>> addIpEntry(
            @Valid @RequestBody IpWhitelistEntryRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        IpWhitelistEntryDto entry = adminSettingsService.addIpEntry(request, currentUser.getId());
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED)
                .body(ApiResponse.success(entry, "IP rule added"));
    }

    @PutMapping("/security/ip-whitelist/{id}")
    public ResponseEntity<ApiResponse<IpWhitelistEntryDto>> updateIpEntry(
            @PathVariable Long id,
            @Valid @RequestBody IpWhitelistEntryRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        IpWhitelistEntryDto entry = adminSettingsService.updateIpEntry(id, request, currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success(entry, "IP rule updated"));
    }

    @DeleteMapping("/security/ip-whitelist/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteIpEntry(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        adminSettingsService.deleteIpEntry(id, currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success(null, "IP rule deleted"));
    }

    @GetMapping("/system-health")
    public ResponseEntity<ApiResponse<SystemHealthResponse>> systemHealth() {
        return ResponseEntity.ok(ApiResponse.success(systemHealthService.checkHealth()));
    }
}
