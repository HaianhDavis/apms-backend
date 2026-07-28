package com.apms.domain.contract.controller;

import com.apms.domain.contract.dto.ApplyExtractionRequest;
import com.apms.domain.contract.dto.ReviewExtractionClauseRequest;
import com.apms.domain.contract.dto.ReviewExtractionFieldRequest;
import com.apms.domain.contract.entity.PartnerContractExtractionDraft;
import com.apms.domain.contract.entity.PartnerContractClauseVersion;
import com.apms.domain.contract.service.PartnerContractExtractionService;
import com.apms.domain.contract.service.PartnerContractService;
import com.apms.security.UserDetailsImpl;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/partner-contracts/{contractId}")
@RequiredArgsConstructor
public class PartnerContractExtractionController {

    private final PartnerContractExtractionService extractionService;
    private final PartnerContractService partnerContractService;

    @PostMapping("/extractions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF')")
    public PartnerContractExtractionDraft generateExtraction(
            @PathVariable Long contractId,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        return extractionService.generateExtraction(contractId, currentUser.getId());
    }

    @PatchMapping("/extractions/{extractionId}/fields/{fieldKey}")
    @PreAuthorize("hasAnyRole('BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF')")
    public PartnerContractExtractionDraft reviewField(
            @PathVariable Long contractId,
            @PathVariable String extractionId,
            @PathVariable String fieldKey,
            @Valid @RequestBody ReviewExtractionFieldRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        return extractionService.reviewField(contractId, extractionId, fieldKey, request, currentUser.getId());
    }

    @PatchMapping("/extractions/{extractionId}/clauses/{clauseCandidateId}")
    @PreAuthorize("hasAnyRole('BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF')")
    public PartnerContractExtractionDraft reviewClause(
            @PathVariable Long contractId,
            @PathVariable String extractionId,
            @PathVariable String clauseCandidateId,
            @Valid @RequestBody ReviewExtractionClauseRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        return extractionService.reviewClause(contractId, extractionId, clauseCandidateId, request, currentUser.getId());
    }

    @PostMapping("/extractions/{extractionId}/apply")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasAnyRole('BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF')")
    public PartnerContractExtractionDraft applyExtraction(
            @PathVariable Long contractId,
            @PathVariable String extractionId,
            @Valid @RequestBody ApplyExtractionRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        return extractionService.applyExtraction(contractId, extractionId, request, currentUser.getId());
    }

    @PostMapping("/extractions/{extractionId}/regenerate")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF')")
    public PartnerContractExtractionDraft regenerateExtraction(
            @PathVariable Long contractId,
            @PathVariable String extractionId,
            @RequestParam(defaultValue = "false") boolean force,
            @RequestParam(required = false) String comment,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        return extractionService.regenerateExtraction(contractId, extractionId, force, comment, currentUser.getId());
    }

    @GetMapping("/extractions")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAnyRole('BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF')")
    public List<PartnerContractExtractionDraft> listExtractions(
            @PathVariable Long contractId,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        return extractionService.listExtractions(contractId, currentUser.getId());
    }

    @GetMapping("/extractions/{extractionId}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAnyRole('BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF')")
    public PartnerContractExtractionDraft getExtraction(
            @PathVariable Long contractId,
            @PathVariable String extractionId,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        return extractionService.getExtraction(contractId, extractionId, currentUser.getId());
    }

    @GetMapping("/versions/{version}/clauses")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF')")
    public List<PartnerContractClauseVersion> getApprovedClauses(
            @PathVariable Long contractId,
            @PathVariable Integer version,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        return partnerContractService.getApprovedClauses(contractId, version, currentUser.getId());
    }

    @GetMapping("/versions/{version}/clauses/{clauseId}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF')")
    public PartnerContractClauseVersion getApprovedClause(
            @PathVariable Long contractId,
            @PathVariable Integer version,
            @PathVariable String clauseId,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        return partnerContractService.getApprovedClause(contractId, version, clauseId, currentUser.getId());
    }
}
