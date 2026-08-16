package com.apms.domain.admin.controller;

import com.apms.common.response.ApiResponse;
import com.apms.domain.admin.dto.SystemHealthResponse;
import com.apms.domain.admin.service.SystemHealthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/system-health")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class SystemHealthController {

    private final SystemHealthService systemHealthService;

    @GetMapping
    public ResponseEntity<ApiResponse<SystemHealthResponse>> getSystemHealth() {
        SystemHealthResponse health = systemHealthService.checkHealth();
        return ResponseEntity.ok(ApiResponse.success(health));
    }
}
