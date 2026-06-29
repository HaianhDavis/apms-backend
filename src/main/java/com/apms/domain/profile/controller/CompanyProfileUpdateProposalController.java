package com.apms.domain.profile.controller;

import com.apms.domain.ai.dto.MergeExtractionsIntoProposalRequest;
import com.apms.domain.ai.dto.MergeProposalResponse;
import com.apms.domain.ai.service.ExtractionMergeService;
import com.apms.domain.profile.dto.CompanyProfileUpdateProposalResponse;
import com.apms.domain.profile.dto.CreateCompanyProfileUpdateProposalRequest;
import com.apms.domain.profile.service.CompanyProfileUpdateProposalService;
import com.apms.security.UserDetailsImpl;
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

    @PostMapping("/projects/{projectId}/tasks/{taskId}/profile-update-proposals")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAnyRole('BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF')")
    public ResponseEntity<CompanyProfileUpdateProposalResponse> createProposal(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @Valid @RequestBody CreateCompanyProfileUpdateProposalRequest request) {
        return ResponseEntity.ok(proposalService.createProposal(projectId, taskId, request));
    }

    /**
     * POST /api/v1/projects/{projectId}/tasks/{taskId}/profile-update-proposals/from-extractions
     *
     * Merges selected AI extraction results against the current approved CompanyProfile
     * and creates a DRAFT CompanyProfileUpdateProposal with field evidence.
     * Does NOT update the CompanyProfile — Manager approval via the review endpoint is still required.
     */
    @PostMapping("/projects/{projectId}/tasks/{taskId}/profile-update-proposals/from-extractions")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAnyRole('BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF')")
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
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAnyRole('BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF')")
    public ResponseEntity<CompanyProfileUpdateProposalResponse> getProposal(@PathVariable String id) {
        return ResponseEntity.ok(proposalService.getProposal(id));
    }
}
