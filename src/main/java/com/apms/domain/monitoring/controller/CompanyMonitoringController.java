package com.apms.domain.monitoring.controller;

import com.apms.common.enums.MonitoringStatus;
import com.apms.domain.monitoring.dto.*;
import com.apms.domain.monitoring.service.CompanyMonitoringService;
import com.apms.security.UserDetailsImpl;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/company-monitoring")
@RequiredArgsConstructor
public class CompanyMonitoringController {

    private final CompanyMonitoringService service;

    @PostMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<CompanyMonitoringAssignmentResponse> assignMonitor(
            @Valid @RequestBody CompanyMonitoringAssignmentRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        // Additional company scope validation should happen in service
        return ResponseEntity.ok(service.assignMonitor(request, currentUser.getId()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<CompanyMonitoringAssignmentResponse> updateAssignment(
            @PathVariable Long id,
            @Valid @RequestBody CompanyMonitoringUpdateRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        return ResponseEntity.ok(service.updateAssignment(id, request, currentUser.getId()));
    }

    @PatchMapping("/{id}/pause")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<CompanyMonitoringAssignmentResponse> pauseAssignment(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        return ResponseEntity.ok(service.updateStatus(id, MonitoringStatus.PAUSED, currentUser.getId()));
    }

    @PatchMapping("/{id}/resume")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<CompanyMonitoringAssignmentResponse> resumeAssignment(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        return ResponseEntity.ok(service.updateStatus(id, MonitoringStatus.ACTIVE, currentUser.getId()));
    }

    @PostMapping("/{id}/reviews")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('BUSINESS_DEVELOPMENT_STAFF')")
    public ResponseEntity<CompanyMonitoringReviewResponse> submitReview(
            @PathVariable Long id,
            @Valid @RequestBody CompanyMonitoringReviewRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        return ResponseEntity.ok(service.submitReview(id, request, currentUser.getId()));
    }

    @GetMapping("/reviews")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('BUSINESS_DEVELOPMENT_MANAGER') or hasRole('BUSINESS_DEVELOPMENT_STAFF')")
    public ResponseEntity<Page<CompanyMonitoringReviewResponse>> getMonitoringHistory(
            @AuthenticationPrincipal UserDetailsImpl currentUser,
            Pageable pageable) {
        return ResponseEntity.ok(service.getMonitoringHistory(currentUser.getId(), pageable));
    }

    @GetMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<Page<CompanyMonitoringAssignmentResponse>> getAllAssignments(
            @AuthenticationPrincipal UserDetailsImpl currentUser,
            Pageable pageable) {
        return ResponseEntity.ok(service.getAllAssignments(currentUser.getId(), pageable));
    }

    @GetMapping("/my")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('BUSINESS_DEVELOPMENT_STAFF')")
    public ResponseEntity<Page<CompanyMonitoringAssignmentResponse>> getMyAssignments(
            @AuthenticationPrincipal UserDetailsImpl currentUser,
            Pageable pageable) {
        return ResponseEntity.ok(service.getMyAssignments(currentUser.getId(), pageable));
    }

    @GetMapping("/due")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<Page<CompanyMonitoringAssignmentResponse>> getDueOrOverdueAssignments(
            @AuthenticationPrincipal UserDetailsImpl currentUser,
            Pageable pageable) {
        return ResponseEntity.ok(service.getDueOrOverdueAssignments(currentUser.getId(), pageable));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAnyRole('BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF', 'BUSINESS_OWNER')")
    public ResponseEntity<CompanyMonitoringAssignmentResponse> getAssignment(@PathVariable Long id) {
        return ResponseEntity.ok(service.getAssignment(id));
    }

    @GetMapping("/company/{companyProfileId}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAnyRole('BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF', 'BUSINESS_OWNER')")
    public ResponseEntity<CompanyMonitoringAssignmentResponse> getAssignmentByCompany(@PathVariable String companyProfileId) {
        return ResponseEntity.ok(service.getAssignmentByCompany(companyProfileId).orElse(null));
    }
}
