package com.apms.domain.dashboard.controller;

import com.apms.common.response.ApiResponse;
import com.apms.domain.dashboard.dto.DashboardSummaryDto;
import com.apms.domain.dashboard.service.DashboardService;
import com.apms.domain.graph.dto.GraphCompanyDto;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF')")
    public ResponseEntity<ApiResponse<DashboardSummaryDto>> getSummary(
            @org.springframework.web.bind.annotation.RequestParam(required = false) Boolean createdByMe,
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.apms.security.UserDetailsImpl currentUser) {
        Long managerId = (createdByMe != null && createdByMe) ? currentUser.getId() : null;
        return ResponseEntity.ok(ApiResponse.success(dashboardService.getSummary(managerId)));
    }

    @GetMapping("/partners")
    @PreAuthorize("hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<ApiResponse<List<GraphCompanyDto>>> getPartners() {
        return ResponseEntity.ok(ApiResponse.success(dashboardService.getPartners()));
    }

    @GetMapping("/competitors")
    @PreAuthorize("hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<ApiResponse<List<GraphCompanyDto>>> getCompetitors() {
        return ResponseEntity.ok(ApiResponse.success(dashboardService.getCompetitors()));
    }

    @GetMapping("/suppliers")
    @PreAuthorize("hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<ApiResponse<List<GraphCompanyDto>>> getSuppliers() {
        return ResponseEntity.ok(ApiResponse.success(dashboardService.getSuppliers()));
    }

    @GetMapping("/potential-partners")
    @PreAuthorize("hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<ApiResponse<List<GraphCompanyDto>>> getPotentialPartners() {
        return ResponseEntity.ok(ApiResponse.success(dashboardService.getPotentialPartners()));
    }

    @GetMapping("/recent-scores")
    @PreAuthorize("hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF')")
    public ResponseEntity<ApiResponse<List<Object>>> getRecentScores() {
        // Mock empty response to prevent 500 errors on frontend
        return ResponseEntity.ok(ApiResponse.success(List.of()));
    }

    @GetMapping("/manager/review-history")
    @PreAuthorize("hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER', 'SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<List<com.apms.domain.dashboard.dto.ManagerReviewHistoryItemResponse>>> getManagerReviewHistory(
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.apms.security.UserDetailsImpl currentUser) {
        Long managerId = currentUser != null ? currentUser.getId() : null;
        return ResponseEntity.ok(ApiResponse.success(dashboardService.getManagerReviewHistory(managerId)));
    }
}
