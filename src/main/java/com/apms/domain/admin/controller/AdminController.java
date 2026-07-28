package com.apms.domain.admin.controller;

import com.apms.common.response.ApiResponse;
import com.apms.common.response.PageResponse;
import com.apms.domain.admin.dto.*;
import com.apms.domain.admin.service.AdminUserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AdminController {

    private final AdminUserService adminUserService;

    @GetMapping("/accounts")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'BUSINESS_OWNER')")
    public ResponseEntity<ApiResponse<PageResponse<AccountAdminResponse>>> getAccounts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(PageResponse.of(adminUserService.getAccounts(PageRequest.of(page, size)))));
    }

    @GetMapping("/accounts/search")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'BUSINESS_OWNER', 'BUSINESS_DIRECTOR', 'BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<ApiResponse<java.util.List<AccountAdminResponse>>> searchAccounts(
            @RequestParam(required = false, defaultValue = "") String email) {
        return ResponseEntity.ok(ApiResponse.success(adminUserService.searchAccountsByEmail(email)));
    }

    @GetMapping("/accounts/{id}")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'BUSINESS_OWNER')")
    public ResponseEntity<ApiResponse<AccountAdminResponse>> getAccount(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(adminUserService.getAccount(id)));
    }

    @PostMapping("/accounts")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'BUSINESS_OWNER')")
    public ResponseEntity<ApiResponse<AccountAdminResponse>> createAccount(@Valid @RequestBody CreateAccountRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(adminUserService.createAccount(request), "Account created"));
    }

    @PutMapping("/accounts/{id}")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'BUSINESS_OWNER')")
    public ResponseEntity<ApiResponse<AccountAdminResponse>> updateAccount(
            @PathVariable Long id,
            @Valid @RequestBody UpdateAccountRequest request) {
        return ResponseEntity.ok(ApiResponse.success(adminUserService.updateAccount(id, request), "Account updated"));
    }

    @PatchMapping("/accounts/{id}/status")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'BUSINESS_OWNER')")
    public ResponseEntity<ApiResponse<AccountAdminResponse>> toggleAccountStatus(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(adminUserService.toggleAccountStatus(id), "Account status updated"));
    }

    @GetMapping("/admin/roles")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'BUSINESS_OWNER')")
    public ResponseEntity<ApiResponse<java.util.List<RoleSummaryDto>>> getRoles() {
        return ResponseEntity.ok(ApiResponse.success(adminUserService.getRoles()));
    }

    @GetMapping("/permissions")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'BUSINESS_OWNER')")
    public ResponseEntity<ApiResponse<java.util.List<PermissionDto>>> getPermissions() {
        return ResponseEntity.ok(ApiResponse.success(adminUserService.getPermissions()));
    }

    @GetMapping("/admin/audit-logs")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'BUSINESS_OWNER', 'BUSINESS_DIRECTOR')")
    public ResponseEntity<ApiResponse<PageResponse<AuditLogResponse>>> getAuditLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(PageResponse.of(adminUserService.getAuditLogs(PageRequest.of(page, size)))));
    }

    @GetMapping("/admin/settings")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'BUSINESS_OWNER')")
    public ResponseEntity<ApiResponse<SystemSettingsResponseDto>> getSettings() {
        return ResponseEntity.ok(ApiResponse.success(adminUserService.getSystemSettings()));
    }

    @PutMapping("/admin/settings")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'BUSINESS_OWNER')")
    public ResponseEntity<ApiResponse<SystemSettingsResponseDto>> updateSettings(@Valid @RequestBody SystemSettingsDto dto) {
        return ResponseEntity.ok(ApiResponse.success(adminUserService.updateSystemSettings(dto), "System settings updated"));
    }
}
