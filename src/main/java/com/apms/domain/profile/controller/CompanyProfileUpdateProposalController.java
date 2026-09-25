package com.apms.domain.profile.controller;

import com.apms.domain.ai.dto.MergeExtractionsIntoProposalRequest;
import com.apms.domain.ai.dto.MergeProposalResponse;
import com.apms.domain.ai.service.ExtractionMergeService;
import com.apms.domain.profile.dto.CompanyProfileUpdateProposalResponse;
import com.apms.domain.profile.dto.CreateCompanyProfileUpdateProposalRequest;
import com.apms.domain.profile.service.CompanyProfileUpdateProposalService;
import com.apms.security.UserDetailsImpl;
import com.apms.security.UserDetailsImpl;
import com.apms.domain.document.service.StorageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CompanyProfileUpdateProposalController {

    private final CompanyProfileUpdateProposalService proposalService;
    private final ExtractionMergeService mergeService;
    private final StorageService storageService;

    @PostMapping("/projects/{projectId}/tasks/{taskId}/profile-update-proposals")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or (hasAnyRole('BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF') and @companyScope.canAccessProject(#projectId))")
    public ResponseEntity<CompanyProfileUpdateProposalResponse> createProposal(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @Valid @RequestBody CreateCompanyProfileUpdateProposalRequest request) {
        return ResponseEntity.ok(proposalService.createProposal(projectId, taskId, request));
    }

    /**
     * POST /api/v1/projects/{projectId}/tasks/{taskId}/profile-update-proposals/from-extractions
     *
     * Generates a DRAFT CompanyProfileUpdateProposal from the selected extraction results.
     * Staff may call this multiple times with different extraction subsets
     * to create multiple independent drafts as reviewable alternatives.
     * Does NOT update the CompanyProfile — Manager approval via the review endpoint is still required.
     */
    @PostMapping("/projects/{projectId}/tasks/{taskId}/profile-update-proposals/from-extractions")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or (hasAnyRole('BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF') and @companyScope.canAccessProject(#projectId))")
    public ResponseEntity<MergeProposalResponse> createProposalFromExtractions(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @Valid @RequestBody MergeExtractionsIntoProposalRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        MergeProposalResponse response = mergeService.createProfileUpdateProposalFromExtractions(
                projectId, taskId, request.getCompanyProfileId(), request.getExtractionIds(),
                request.getChangeSummary(), currentUser.getId());

        return ResponseEntity.ok(response);
    }

    @GetMapping("/profile-update-proposals/{id}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or (hasAnyRole('BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF') and @companyScope.canAccessProposal(#id))")
    public ResponseEntity<CompanyProfileUpdateProposalResponse> getProposal(@PathVariable String id) {
        return ResponseEntity.ok(proposalService.getProposal(id));
    }

    @PatchMapping("/profile-update-proposals/{id}/approve")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<CompanyProfileUpdateProposalResponse> approveProposal(
            @PathVariable String id,
            @RequestBody(required = false) com.apms.domain.profile.dto.ReviewDecisionRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        String comment = request != null ? request.getReviewComment() : null;
        return ResponseEntity.ok(proposalService.approveMonitoringProposal(id, currentUser.getId(), comment));
    }

    @PatchMapping("/profile-update-proposals/{id}/reject")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<CompanyProfileUpdateProposalResponse> rejectProposal(
            @PathVariable String id,
            @RequestBody(required = false) com.apms.domain.profile.dto.ReviewDecisionRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        String comment = request != null ? request.getReviewComment() : null;
        return ResponseEntity.ok(proposalService.rejectMonitoringProposal(id, currentUser.getId(), comment));
    }

    @PatchMapping("/profile-update-proposals/{id}/withdraw")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('BUSINESS_DEVELOPMENT_STAFF') or hasRole('BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<CompanyProfileUpdateProposalResponse> withdrawProposal(
            @PathVariable String id,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        return ResponseEntity.ok(proposalService.withdrawMonitoringProposal(id, currentUser.getId()));
    }

    @PostMapping("/profile-update-proposals/monitoring")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAnyRole('BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF')")
    public ResponseEntity<CompanyProfileUpdateProposalResponse> createMonitoringProposal(
            @Valid @RequestBody CreateCompanyProfileUpdateProposalRequest request) {
        return ResponseEntity.ok(proposalService.createMonitoringProposal(request));
    }

    @GetMapping("/company-profiles/{companyProfileId}/pending-proposals")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAnyRole('BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF')")
    public ResponseEntity<java.util.List<CompanyProfileUpdateProposalResponse>> getPendingProposals(
            @PathVariable String companyProfileId) {
        return ResponseEntity.ok(proposalService.getPendingProposalsByCompany(companyProfileId));
    }

    @PostMapping("/profile-update-proposals/monitoring/evidence/upload")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('BUSINESS_DEVELOPMENT_STAFF') or hasRole('BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<java.util.Map<String, String>> uploadEvidenceImage(
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        String storedId = storageService.store(file);
        return ResponseEntity.ok(java.util.Map.of(
                "evidenceImageId", storedId,
                "originalFileName", file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown"
        ));
    }

    @GetMapping("/profile-update-proposals/monitoring/evidence/{id}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('BUSINESS_DEVELOPMENT_STAFF') or hasRole('BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<org.springframework.core.io.Resource> getEvidenceImage(@PathVariable String id) {
        try {
            java.nio.file.Path filePath = storageService.load(id);
            org.springframework.core.io.Resource resource = storageService.loadAsResource(id);
            String contentType = java.nio.file.Files.probeContentType(filePath);
            if (contentType == null) {
                contentType = org.springframework.http.MediaType.APPLICATION_OCTET_STREAM_VALUE;
            }
            return ResponseEntity.ok()
                    .contentType(org.springframework.http.MediaType.parseMediaType(contentType))
                    .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + resource.getFilename() + "\"")
                    .body(resource);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }
}
