package com.apms.domain.dashboard.controller;

import com.apms.common.response.ApiResponse;
import com.apms.domain.dashboard.dto.DashboardSummaryDto;
import com.apms.domain.dashboard.service.DashboardService;
import com.apms.domain.graph.dto.GraphCompanyDto;
import com.apms.domain.score.dto.ScoreSnapshotDto;
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
    @PreAuthorize("hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DIRECTOR', 'BUSINESS_DEVELOPMENT_MANAGER', 'RESEARCH_STAFF')")
    public ResponseEntity<ApiResponse<DashboardSummaryDto>> getSummary() {
        return ResponseEntity.ok(ApiResponse.success(dashboardService.getSummary()));
    }

    @GetMapping("/partners")
    @PreAuthorize("hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DIRECTOR', 'BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<ApiResponse<List<GraphCompanyDto>>> getPartners() {
        return ResponseEntity.ok(ApiResponse.success(dashboardService.getPartners()));
    }

    @GetMapping("/competitors")
    @PreAuthorize("hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DIRECTOR', 'BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<ApiResponse<List<GraphCompanyDto>>> getCompetitors() {
        return ResponseEntity.ok(ApiResponse.success(dashboardService.getCompetitors()));
    }

    @GetMapping("/suppliers")
    @PreAuthorize("hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DIRECTOR', 'BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<ApiResponse<List<GraphCompanyDto>>> getSuppliers() {
        return ResponseEntity.ok(ApiResponse.success(dashboardService.getSuppliers()));
    }

    @GetMapping("/potential-partners")
    @PreAuthorize("hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DIRECTOR', 'BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<ApiResponse<List<GraphCompanyDto>>> getPotentialPartners() {
        return ResponseEntity.ok(ApiResponse.success(dashboardService.getPotentialPartners()));
    }

    @GetMapping("/recent-scores")
    @PreAuthorize("hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DIRECTOR', 'BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<ApiResponse<List<ScoreSnapshotDto>>> getRecentScores() {
        return ResponseEntity.ok(ApiResponse.success(dashboardService.getRecentScores()));
    }
}
