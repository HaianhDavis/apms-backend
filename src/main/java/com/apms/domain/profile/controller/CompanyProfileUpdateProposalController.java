package com.apms.domain.profile.controller;

import com.apms.domain.profile.dto.CompanyProfileUpdateProposalResponse;
import com.apms.domain.profile.dto.CreateCompanyProfileUpdateProposalRequest;
import com.apms.domain.profile.service.CompanyProfileUpdateProposalService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CompanyProfileUpdateProposalController {

    private final CompanyProfileUpdateProposalService proposalService;

    @PostMapping("/projects/{projectId}/tasks/{taskId}/profile-update-proposals")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAnyRole('BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF')")
    public ResponseEntity<CompanyProfileUpdateProposalResponse> createProposal(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @Valid @RequestBody CreateCompanyProfileUpdateProposalRequest request) {
        return ResponseEntity.ok(proposalService.createProposal(projectId, taskId, request));
    }

    @GetMapping("/profile-update-proposals/{id}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAnyRole('BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF')")
    public ResponseEntity<CompanyProfileUpdateProposalResponse> getProposal(@PathVariable String id) {
        return ResponseEntity.ok(proposalService.getProposal(id));
    }
}
