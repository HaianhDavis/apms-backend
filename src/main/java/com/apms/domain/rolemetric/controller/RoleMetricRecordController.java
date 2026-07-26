package com.apms.domain.rolemetric.controller;

import com.apms.domain.rolemetric.dto.*;
import com.apms.domain.rolemetric.service.RoleMetricRecordService;
import com.apms.domain.rolemetric.repository.RoleMetricRecordRepository;
import com.apms.domain.rolemetric.repository.RoleMetricRecordVersionRepository;
import com.apms.domain.rolemetric.repository.RoleMetricEvidenceVersionRepository;
import com.apms.domain.rolemetric.entity.RoleMetricRecord;
import com.apms.domain.rolemetric.entity.RoleMetricRecordVersion;
import com.apms.common.exception.ResourceNotFoundException;
import com.apms.common.exception.BusinessValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.stream.Collectors;
import java.util.List;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/role-metrics")
@RequiredArgsConstructor
public class RoleMetricRecordController {

    private final RoleMetricRecordService roleMetricRecordService;
    private final RoleMetricRecordRepository recordRepository;
    private final RoleMetricRecordVersionRepository versionRepository;
    private final RoleMetricEvidenceVersionRepository evidenceVersionRepository;

    @PostMapping
    @PreAuthorize("@projectSecurity.isStaff(#projectId)")
    public ResponseEntity<RoleMetricResponse> createDraft(
            @PathVariable Long projectId,
            @RequestBody CreateRoleMetricRequest request) {
        return ResponseEntity.ok(roleMetricRecordService.createDraft(projectId, request));
    }

    @PatchMapping("/{metricId}")
    @PreAuthorize("@projectSecurity.isStaff(#projectId)")
    public ResponseEntity<RoleMetricResponse> updateDraft(
            @PathVariable Long projectId,
            @PathVariable Long metricId,
            @RequestBody UpdateRoleMetricRequest request) {
        return ResponseEntity.ok(roleMetricRecordService.updateDraft(projectId, metricId, request));
    }

    @PostMapping("/{metricId}/evidences")
    @PreAuthorize("@projectSecurity.isStaff(#projectId)")
    public ResponseEntity<RoleMetricEvidenceResponse> attachEvidence(
            @PathVariable Long projectId,
            @PathVariable Long metricId,
            @RequestBody RoleMetricEvidenceRequest request) {
        return ResponseEntity.ok(roleMetricRecordService.attachEvidence(projectId, metricId, request));
    }

    @DeleteMapping("/{metricId}/evidences/{evidenceId}")
    @PreAuthorize("@projectSecurity.isStaff(#projectId)")
    public ResponseEntity<Void> deleteEvidence(
            @PathVariable Long projectId,
            @PathVariable Long metricId,
            @PathVariable Long evidenceId) {
        roleMetricRecordService.deleteEvidence(projectId, metricId, evidenceId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{metricId}/submit")
    @PreAuthorize("@projectSecurity.isStaff(#projectId)")
    public ResponseEntity<RoleMetricResponse> submitForReview(
            @PathVariable Long projectId,
            @PathVariable Long metricId) {
        return ResponseEntity.ok(roleMetricRecordService.submitForReview(projectId, metricId));
    }

    @PostMapping("/{metricId}/review")
    @PreAuthorize("@projectSecurity.isManager(#projectId)")
    public ResponseEntity<RoleMetricResponse> reviewMetric(
            @PathVariable Long projectId,
            @PathVariable Long metricId,
            @RequestBody ReviewRoleMetricRequest request) {
        return ResponseEntity.ok(roleMetricRecordService.reviewMetric(projectId, metricId, request));
    }

    @PostMapping("/{metricId}/revisions")
    @PreAuthorize("@projectSecurity.isStaff(#projectId)")
    public ResponseEntity<RoleMetricResponse> reviseMetric(
            @PathVariable Long projectId,
            @PathVariable Long metricId) {
        return ResponseEntity.ok(roleMetricRecordService.reviseMetric(projectId, metricId));
    }

    @PostMapping("/{metricId}/reopen")
    @PreAuthorize("@projectSecurity.isStaff(#projectId)")
    public ResponseEntity<RoleMetricResponse> reopenMetric(
            @PathVariable Long projectId,
            @PathVariable Long metricId) {
        return ResponseEntity.ok(roleMetricRecordService.reopenMetric(projectId, metricId));
    }

    // Reads

    @GetMapping
    @PreAuthorize("@projectSecurity.isStaffOrManager(#projectId)")
    public ResponseEntity<List<RoleMetricResponse>> listWorkingMetrics(
            @PathVariable Long projectId) {
        return ResponseEntity.ok(recordRepository.findByProjectId(projectId).stream()
            .map(roleMetricRecordService::mapToResponse)
            .collect(Collectors.toList()));
    }

    @GetMapping("/approved")
    @PreAuthorize("@projectSecurity.isProjectReadable(#projectId)")
    public ResponseEntity<List<RoleMetricVersionResponse>> listApprovedMetrics(
            @PathVariable Long projectId) {
        return ResponseEntity.ok(roleMetricRecordService.listApprovedMetrics(projectId));
    }

    @GetMapping("/{metricId}")
    @PreAuthorize("@projectSecurity.isStaffOrManager(#projectId)")
    public ResponseEntity<RoleMetricResponse> getWorkingMetric(
            @PathVariable Long projectId,
            @PathVariable Long metricId) {
        return ResponseEntity.ok(roleMetricRecordService.getWorkingDetail(projectId, metricId));
    }

    @GetMapping("/{metricId}/current-approved")
    @PreAuthorize("@projectSecurity.isProjectReadable(#projectId)")
    public ResponseEntity<RoleMetricVersionResponse> getCurrentApproved(
            @PathVariable Long projectId,
            @PathVariable Long metricId) {
        return ResponseEntity.ok(roleMetricRecordService.getCurrentApproved(projectId, metricId));
    }

    @GetMapping("/{metricId}/versions/{versionNumber}")
    @PreAuthorize("@projectSecurity.isProjectReadable(#projectId)")
    public ResponseEntity<RoleMetricVersionResponse> getVersionByNumber(
            @PathVariable Long projectId,
            @PathVariable Long metricId,
            @PathVariable Integer versionNumber) {
        return ResponseEntity.ok(roleMetricRecordService.getVersionByNumber(projectId, metricId, versionNumber));
    }

    @GetMapping("/{metricId}/versions")
    @PreAuthorize("@projectSecurity.isProjectReadable(#projectId)")
    public ResponseEntity<List<RoleMetricVersionResponse>> getVersions(
            @PathVariable Long projectId,
            @PathVariable Long metricId) {
        return ResponseEntity.ok(roleMetricRecordService.getVersions(projectId, metricId));
    }
}
