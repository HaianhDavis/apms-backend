package com.apms.domain.financial.controller;

import com.apms.domain.contract.dto.ConfirmCompanyMatchRequest;
import com.apms.domain.financial.dto.CreateFinancialMetricRequest;
import com.apms.domain.financial.dto.CreateFinancialReportRequest;
import com.apms.domain.financial.dto.FinancialResearchResponse;
import com.apms.domain.financial.dto.UpdateFinancialMetricRequest;
import com.apms.domain.financial.service.FinancialResearchService;
import com.apms.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class FinancialResearchController {

    private final FinancialResearchService researchService;

    @GetMapping("/projects/{projectId}/tasks/{taskId}/financial-research")
    public ResponseEntity<FinancialResearchResponse> getResearch(
            @PathVariable Long projectId,
            @PathVariable Long taskId) {
        return researchService.getResearch(projectId, taskId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/projects/{projectId}/tasks/{taskId}/financial-research/reports")
    public ResponseEntity<FinancialResearchResponse> addReport(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @RequestBody CreateFinancialReportRequest request) {
        return ResponseEntity.ok(researchService.addReport(projectId, taskId, request));
    }

    @DeleteMapping("/projects/{projectId}/tasks/{taskId}/financial-research/reports/{reportId}")
    public ResponseEntity<FinancialResearchResponse> removeReport(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @PathVariable String reportId) {
        return ResponseEntity.ok(researchService.removeReport(projectId, taskId, reportId));
    }

    @PostMapping("/projects/{projectId}/tasks/{taskId}/financial-research/reports/{reportId}/extract")
    public ResponseEntity<FinancialResearchResponse> extractReport(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @PathVariable String reportId) {
        return ResponseEntity.ok(researchService.extractReport(projectId, taskId, reportId));
    }

    @PostMapping("/projects/{projectId}/tasks/{taskId}/financial-research/reports/{reportId}/re-extract")
    public ResponseEntity<FinancialResearchResponse> reExtractReport(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @PathVariable String reportId) {
        return ResponseEntity.ok(researchService.reExtractReport(projectId, taskId, reportId));
    }

    @PostMapping("/projects/{projectId}/tasks/{taskId}/financial-research/reports/{reportId}/cancel-extract")
    public ResponseEntity<FinancialResearchResponse> cancelExtractReport(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @PathVariable String reportId) {
        return ResponseEntity.ok(researchService.cancelExtraction(projectId, taskId, reportId));
    }

    @PostMapping("/projects/{projectId}/tasks/{taskId}/financial-research/reports/{reportId}/confirm-company")
    @PreAuthorize("hasRole('BUSINESS_DEVELOPMENT_STAFF') or hasRole('BUSINESS_DEVELOPMENT_MANAGER') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<FinancialResearchResponse> confirmCompanyMatch(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @PathVariable String reportId,
            @RequestBody ConfirmCompanyMatchRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        Long userId = currentUser != null ? currentUser.getId() : null;
        return ResponseEntity.ok(researchService.confirmCompanyMatch(projectId, taskId, reportId, request.isConfirmed(), userId));
    }

    @PostMapping("/projects/{projectId}/tasks/{taskId}/financial-research/metrics")
    public ResponseEntity<FinancialResearchResponse> addManualMetric(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @RequestBody CreateFinancialMetricRequest request) {
        return ResponseEntity.ok(researchService.addManualMetric(projectId, taskId, request));
    }

    @PutMapping("/projects/{projectId}/tasks/{taskId}/financial-research/metrics/{metricId}")
    public ResponseEntity<FinancialResearchResponse> updateMetric(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @PathVariable String metricId,
            @RequestBody UpdateFinancialMetricRequest request) {
        return ResponseEntity.ok(researchService.updateMetric(projectId, taskId, metricId, request));
    }

    @DeleteMapping("/projects/{projectId}/tasks/{taskId}/financial-research/metrics/{metricId}")
    public ResponseEntity<FinancialResearchResponse> removeMetric(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @PathVariable String metricId) {
        return ResponseEntity.ok(researchService.removeMetric(projectId, taskId, metricId));
    }

    @PostMapping("/projects/{projectId}/tasks/{taskId}/financial-research/metrics/{metricId}/verify")
    public ResponseEntity<FinancialResearchResponse> verifyMetric(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @PathVariable String metricId) {
        return ResponseEntity.ok(researchService.verifyMetric(projectId, taskId, metricId));
    }

    @PostMapping("/projects/{projectId}/tasks/{taskId}/financial-research/metrics/{metricId}/unverify")
    public ResponseEntity<FinancialResearchResponse> unverifyMetric(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @PathVariable String metricId) {
        return ResponseEntity.ok(researchService.unverifyMetric(projectId, taskId, metricId));
    }

    @PostMapping("/projects/{projectId}/tasks/{taskId}/financial-research/reports/{reportId}/verify-all")
    public ResponseEntity<FinancialResearchResponse> verifyAllMetrics(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @PathVariable String reportId) {
        return ResponseEntity.ok(researchService.verifyAllMetricsForReport(projectId, taskId, reportId));
    }

    @PostMapping("/projects/{projectId}/tasks/{taskId}/financial-research/reports/{reportId}/unverify-all")
    public ResponseEntity<FinancialResearchResponse> unverifyAllMetrics(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @PathVariable String reportId) {
        return ResponseEntity.ok(researchService.unverifyAllMetricsForReport(projectId, taskId, reportId));
    }

    @PostMapping("/projects/{projectId}/tasks/{taskId}/financial-research/reports/{reportId}/review")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAnyRole('BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<FinancialResearchResponse> reviewReport(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @PathVariable String reportId,
            @RequestBody com.apms.domain.financial.dto.ReviewFinancialReportRequest request) {
        return ResponseEntity.ok(researchService.reviewReport(projectId, taskId, reportId, request));
    }

    @PostMapping("/projects/{projectId}/tasks/{taskId}/financial-research/recall-submission")
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('BUSINESS_DEVELOPMENT_STAFF', 'SYSTEM_ADMIN')")
    public ResponseEntity<FinancialResearchResponse> recallSubmission(
            @PathVariable Long projectId,
            @PathVariable Long taskId) {
        return ResponseEntity.ok(researchService.recallSubmission(projectId, taskId));
    }

    @GetMapping("/company-profiles/{companyProfileId}/financials/research")
    public ResponseEntity<List<FinancialResearchResponse>> getApprovedFinancials(
            @PathVariable String companyProfileId) {
        return ResponseEntity.ok(researchService.getApprovedFinancials(companyProfileId));
    }
}
