package com.apms.domain.insight.controller;

import com.apms.common.response.ApiResponse;
import com.apms.domain.insight.service.InsightsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class InsightsController {

    private final InsightsService insightsService;

    @GetMapping("/dashboard/activity")
    @PreAuthorize("hasRole('BUSINESS_OWNER')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> activity() {
        return ResponseEntity.ok(ApiResponse.success(insightsService.getActivityTimeline()));
    }

    @GetMapping("/dashboard/user-registration")
    @PreAuthorize("hasRole('BUSINESS_OWNER')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> userRegistration() {
        return ResponseEntity.ok(ApiResponse.success(insightsService.getUserRegistrationSeries()));
    }

    @GetMapping("/dashboard/login-activity")
    @PreAuthorize("hasRole('BUSINESS_OWNER')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> loginActivity() {
        return ResponseEntity.ok(ApiResponse.success(insightsService.getLoginActivitySeries()));
    }

    @GetMapping("/dashboard/system-health")
    @PreAuthorize("hasRole('BUSINESS_OWNER')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> systemHealth() {
        return ResponseEntity.ok(ApiResponse.success(insightsService.getSystemHealth()));
    }

    @GetMapping("/dashboard/role-distribution")
    @PreAuthorize("hasRole('BUSINESS_OWNER')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> roleDistribution() {
        return ResponseEntity.ok(ApiResponse.success(insightsService.getRoleDistribution()));
    }

    @GetMapping("/risk-monitoring")
    @PreAuthorize("hasAnyRole('BUSINESS_DIRECTOR', 'BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> riskMonitoring() {
        return ResponseEntity.ok(ApiResponse.success(insightsService.getRiskMonitoring()));
    }

    @GetMapping("/kpi/team")
    @PreAuthorize("hasAnyRole('BUSINESS_DIRECTOR', 'BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> teamKpi() {
        return ResponseEntity.ok(ApiResponse.success(insightsService.getTeamKpi()));
    }

    @GetMapping("/reports")
    @PreAuthorize("hasAnyRole('BUSINESS_DIRECTOR', 'BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> reports() {
        return ResponseEntity.ok(ApiResponse.success(insightsService.getReports()));
    }

    @GetMapping("/analysis/history")
    @PreAuthorize("hasAnyRole('BUSINESS_DIRECTOR', 'BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> analysisHistory() {
        return ResponseEntity.ok(ApiResponse.success(insightsService.getAnalysisHistory()));
    }

    @GetMapping("/training/sessions")
    @PreAuthorize("hasRole('RESEARCH_STAFF')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> trainingSessions() {
        return ResponseEntity.ok(ApiResponse.success(insightsService.getTrainingSessions()));
    }

    @GetMapping("/training/questions")
    @PreAuthorize("hasRole('RESEARCH_STAFF')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> trainingQuestions() {
        return ResponseEntity.ok(ApiResponse.success(insightsService.getTrainingQuestions()));
    }

    @GetMapping("/learning/courses")
    @PreAuthorize("hasRole('RESEARCH_STAFF')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> learningCourses() {
        return ResponseEntity.ok(ApiResponse.success(insightsService.getLearningCourses()));
    }
}

