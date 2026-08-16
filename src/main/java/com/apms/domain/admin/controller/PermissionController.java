package com.apms.domain.admin.controller;

import com.apms.common.response.ApiResponse;
import com.apms.domain.admin.dto.PermissionDto;
import com.apms.domain.admin.service.PermissionService;
import com.apms.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/permissions")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class PermissionController {

    private final PermissionService permissionService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<PermissionDto>>> getAllPermissions() {
        return ResponseEntity.ok(ApiResponse.success(permissionService.getAllPermissions()));
    }

    @GetMapping("/roles")
    public ResponseEntity<ApiResponse<Map<String, List<String>>>> getAllRolePermissions() {
        return ResponseEntity.ok(ApiResponse.success(permissionService.getAllRolePermissions()));
    }

    @GetMapping("/roles/{roleKey}")
    public ResponseEntity<ApiResponse<List<String>>> getRolePermissions(@PathVariable String roleKey) {
        return ResponseEntity.ok(ApiResponse.success(
                permissionService.getPermissionNamesForRole(roleKey)));
    }

    @PutMapping("/roles/{roleKey}")
    public ResponseEntity<ApiResponse<Void>> updateRolePermissions(
            @PathVariable String roleKey,
            @RequestBody List<String> permissionNames,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        permissionService.updateRolePermissions(roleKey, permissionNames, currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success(null, "Role permissions updated"));
    }
}
