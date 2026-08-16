package com.apms.domain.monitoring.controller;

import com.apms.domain.monitoring.dto.RelationshipChangeProposalRequest;
import com.apms.domain.monitoring.dto.RelationshipChangeProposalResponse;
import com.apms.domain.monitoring.dto.RelationshipChangeReviewRequest;
import com.apms.domain.monitoring.dto.RelationshipHistoryResponse;
import com.apms.domain.monitoring.service.CompanyRelationshipService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.apms.security.UserDetailsImpl;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CompanyRelationshipController {

    private final CompanyRelationshipService relationshipService;

    @PostMapping("/company-monitoring/{assignmentId}/relationship-changes")
    @PreAuthorize("hasRole('STAFF')")
    public ResponseEntity<RelationshipChangeProposalResponse> proposeRelationshipChange(
            @PathVariable Long assignmentId,
            @Valid @RequestBody RelationshipChangeProposalRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        return ResponseEntity.ok(relationshipService.proposeRelationshipChange(assignmentId, request, currentUser.getId()));
    }

    @GetMapping("/company-profiles/{companyProfileId}/relationship-changes/pending")
    @PreAuthorize("hasAnyRole('BUSINESS_DEVELOPMENT_MANAGER', 'SYSTEM_ADMIN')")
    public ResponseEntity<List<RelationshipChangeProposalResponse>> getPendingProposals(
            @PathVariable String companyProfileId,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        return ResponseEntity.ok(relationshipService.getPendingProposals(companyProfileId, currentUser.getId()));
    }

    @PatchMapping("/relationship-changes/{id}/approve")
    @PreAuthorize("hasAnyRole('BUSINESS_DEVELOPMENT_MANAGER', 'SYSTEM_ADMIN')")
    public ResponseEntity<RelationshipChangeProposalResponse> approveProposal(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        return ResponseEntity.ok(relationshipService.approveProposal(id, currentUser.getId()));
    }

    @PatchMapping("/relationship-changes/{id}/reject")
    @PreAuthorize("hasAnyRole('BUSINESS_DEVELOPMENT_MANAGER', 'SYSTEM_ADMIN')")
    public ResponseEntity<RelationshipChangeProposalResponse> rejectProposal(
            @PathVariable Long id,
            @Valid @RequestBody RelationshipChangeReviewRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        return ResponseEntity.ok(relationshipService.rejectProposal(id, request, currentUser.getId()));
    }

    @GetMapping("/company-profiles/{companyProfileId}/relationship-history")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<RelationshipHistoryResponse>> getRelationshipHistory(
            @PathVariable String companyProfileId) {
        return ResponseEntity.ok(relationshipService.getRelationshipHistory(companyProfileId));
    }
}
