package com.apms.domain.contract.controller;

import com.apms.domain.contract.dto.ReviewExtractionClauseRequest;
import com.apms.domain.contract.dto.ReviewExtractionFieldRequest;
import com.apms.domain.contract.entity.PartnerContractExtractionDraft;
import com.apms.domain.contract.service.PartnerContractExtractionService;
import com.apms.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/tasks/{taskId}/partner-contracts/extractions")
@RequiredArgsConstructor
public class PartnerContractTaskExtractionController {

    private final PartnerContractExtractionService extractionService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF') and @projectSecurity.isMember(#projectId)")
    public PartnerContractExtractionDraft generateExtractionForTask(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @RequestParam String rawDocumentId,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        return extractionService.generateExtractionForTask(taskId, rawDocumentId, currentUser.getId());
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF') and @projectSecurity.isMember(#projectId)")
    public List<PartnerContractExtractionDraft> listExtractionsByTask(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        return extractionService.listExtractionsByTask(taskId, currentUser.getId());
    }

    @GetMapping("/{extractionId}")
    @PreAuthorize("hasAnyRole('BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF') and @projectSecurity.isMember(#projectId)")
    public PartnerContractExtractionDraft getExtractionByTask(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @PathVariable String extractionId,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        return extractionService.getExtractionByTask(taskId, extractionId, currentUser.getId());
    }

    @PatchMapping("/{extractionId}/fields/{fieldKey}")
    @PreAuthorize("hasAnyRole('BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF') and @projectSecurity.isMember(#projectId)")
    public PartnerContractExtractionDraft reviewFieldByTask(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @PathVariable String extractionId,
            @PathVariable String fieldKey,
            @RequestBody ReviewExtractionFieldRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        return extractionService.reviewFieldByTask(taskId, extractionId, fieldKey, request, currentUser.getId());
    }

    @PatchMapping("/{extractionId}/clauses/{clauseCandidateId}")
    @PreAuthorize("hasAnyRole('BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF') and @projectSecurity.isMember(#projectId)")
    public PartnerContractExtractionDraft reviewClauseByTask(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @PathVariable String extractionId,
            @PathVariable String clauseCandidateId,
            @RequestBody ReviewExtractionClauseRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        return extractionService.reviewClauseByTask(taskId, extractionId, clauseCandidateId, request, currentUser.getId());
    }
}
