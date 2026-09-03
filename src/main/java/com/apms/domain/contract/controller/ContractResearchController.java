package com.apms.domain.contract.controller;

import com.apms.domain.contract.dto.*;
import com.apms.domain.contract.model.ContractEntry;
import com.apms.domain.contract.service.ContractResearchService;
import com.apms.security.UserDetailsImpl;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ContractResearchController {

    private final ContractResearchService researchService;
    private final com.apms.domain.profile.service.CompanyProfileAccessService companyProfileAccessService;

    @GetMapping("/projects/{projectId}/tasks/{taskId}/contract-research")
    public ResponseEntity<ContractResearchResponse> getResearch(
            @PathVariable Long projectId,
            @PathVariable Long taskId) {
        return researchService.getResearch(projectId, taskId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/projects/{projectId}/tasks/{taskId}/contract-research/contracts")
    public ResponseEntity<ContractResearchResponse> createContractEntry(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @Valid @RequestBody CreateContractEntryRequest request,
            @AuthenticationPrincipal UserDetailsImpl user) {
        Long userId = user != null ? user.getId() : 1L;
        return ResponseEntity.ok(researchService.createContractEntry(taskId, request, userId));
    }

    @PutMapping("/projects/{projectId}/tasks/{taskId}/contract-research/contracts/{contractId}")
    public ResponseEntity<ContractResearchResponse> updateContractEntry(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @PathVariable String contractId,
            @Valid @RequestBody UpdateContractEntryRequest request,
            @AuthenticationPrincipal UserDetailsImpl user) {
        Long userId = user != null ? user.getId() : 1L;
        return ResponseEntity.ok(researchService.updateContractEntry(taskId, contractId, request, userId));
    }

    @DeleteMapping("/projects/{projectId}/tasks/{taskId}/contract-research/contracts/{contractId}")
    public ResponseEntity<ContractResearchResponse> deleteContractEntry(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @PathVariable String contractId,
            @AuthenticationPrincipal UserDetailsImpl user) {
        Long userId = user != null ? user.getId() : 1L;
        return ResponseEntity.ok(researchService.deleteContractEntry(taskId, contractId, userId));
    }

    @PostMapping("/projects/{projectId}/tasks/{taskId}/contract-research/contracts/{contractId}/extract")
    public ResponseEntity<ContractResearchResponse> extractContract(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @PathVariable String contractId,
            @AuthenticationPrincipal UserDetailsImpl user) {
        Long userId = user != null ? user.getId() : 1L;
        return ResponseEntity.ok(researchService.extractContractEntry(taskId, contractId, userId));
    }

    @PostMapping("/projects/{projectId}/tasks/{taskId}/contract-research/contracts/{contractId}/re-extract")
    public ResponseEntity<ContractResearchResponse> reExtractContract(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @PathVariable String contractId,
            @AuthenticationPrincipal UserDetailsImpl user) {
        Long userId = user != null ? user.getId() : 1L;
        return ResponseEntity.ok(researchService.reExtractContractEntry(taskId, contractId, userId));
    }

    @PostMapping("/projects/{projectId}/tasks/{taskId}/contract-research/contracts/{contractId}/cancel-extract")
    public ResponseEntity<ContractResearchResponse> cancelExtract(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @PathVariable String contractId,
            @AuthenticationPrincipal UserDetailsImpl user) {
        Long userId = user != null ? user.getId() : 1L;
        return ResponseEntity.ok(researchService.cancelExtraction(taskId, contractId, userId));
    }

    @PostMapping("/projects/{projectId}/tasks/{taskId}/contract-research/contracts/{contractId}/resolve-type")
    public ResponseEntity<ContractResearchResponse> resolveContractType(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @PathVariable String contractId,
            @Valid @RequestBody ResolveContractTypeRequest request,
            @AuthenticationPrincipal UserDetailsImpl user) {
        Long userId = user != null ? user.getId() : 1L;
        return ResponseEntity.ok(researchService.resolveContractType(taskId, contractId, request.getConfirmedContractType(), userId));
    }

    @PostMapping("/projects/{projectId}/tasks/{taskId}/contract-research/contracts/{contractId}/confirm-company")
    public ResponseEntity<ContractResearchResponse> confirmCompanyMatch(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @PathVariable String contractId,
            @RequestBody ConfirmCompanyMatchRequest request,
            @AuthenticationPrincipal UserDetailsImpl user) {
        Long userId = user != null ? user.getId() : 1L;
        return ResponseEntity.ok(researchService.confirmCompanyMatch(taskId, contractId, request.isConfirmed(), userId));
    }

    @PutMapping("/projects/{projectId}/tasks/{taskId}/contract-research/contracts/{contractId}/fields/{fieldPath}")
    public ResponseEntity<ContractResearchResponse> updateScalarField(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @PathVariable String contractId,
            @PathVariable String fieldPath,
            @RequestBody UpdateScalarFieldRequest request,
            @AuthenticationPrincipal UserDetailsImpl user) {
        Long userId = user != null ? user.getId() : 1L;
        return ResponseEntity.ok(researchService.updateScalarField(taskId, contractId, fieldPath, request, userId));
    }

    @PostMapping("/projects/{projectId}/tasks/{taskId}/contract-research/contracts/{contractId}/fields/{fieldPath}/verify")
    public ResponseEntity<ContractResearchResponse> verifyScalarField(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @PathVariable String contractId,
            @PathVariable String fieldPath,
            @AuthenticationPrincipal UserDetailsImpl user) {
        Long userId = user != null ? user.getId() : 1L;
        return ResponseEntity.ok(researchService.verifyScalarField(taskId, contractId, fieldPath, userId));
    }

    @PutMapping("/projects/{projectId}/tasks/{taskId}/contract-research/contracts/{contractId}/fields/{fieldPath}/items/{itemId}")
    public ResponseEntity<ContractResearchResponse> updateArrayItem(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @PathVariable String contractId,
            @PathVariable String fieldPath,
            @PathVariable String itemId,
            @RequestBody UpdateArrayItemRequest request,
            @AuthenticationPrincipal UserDetailsImpl user) {
        Long userId = user != null ? user.getId() : 1L;
        return ResponseEntity.ok(researchService.updateArrayItem(taskId, contractId, fieldPath, itemId, request, userId));
    }

    @PostMapping("/projects/{projectId}/tasks/{taskId}/contract-research/contracts/{contractId}/fields/{fieldPath}/items/{itemId}/verify")
    public ResponseEntity<ContractResearchResponse> verifyArrayItem(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @PathVariable String contractId,
            @PathVariable String fieldPath,
            @PathVariable String itemId,
            @AuthenticationPrincipal UserDetailsImpl user) {
        Long userId = user != null ? user.getId() : 1L;
        return ResponseEntity.ok(researchService.verifyArrayItem(taskId, contractId, fieldPath, itemId, userId));
    }

    @PostMapping("/projects/{projectId}/tasks/{taskId}/contract-research/submit")
    @PreAuthorize("hasAnyRole('BUSINESS_DEVELOPMENT_STAFF', 'SYSTEM_ADMIN')")
    public ResponseEntity<ContractResearchResponse> submitResearch(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @Valid @RequestBody SubmitContractResearchRequest request,
            @AuthenticationPrincipal UserDetailsImpl user) {
        Long userId = user != null ? user.getId() : 1L;
        return ResponseEntity.ok(researchService.submitResearch(projectId, taskId, request, userId));
    }

    @PostMapping("/projects/{projectId}/tasks/{taskId}/contract-research/recall-submission")
    @PreAuthorize("hasAnyRole('BUSINESS_DEVELOPMENT_STAFF', 'SYSTEM_ADMIN')")
    public ResponseEntity<ContractResearchResponse> recallSubmission(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @AuthenticationPrincipal UserDetailsImpl user) {
        Long userId = user != null ? user.getId() : 1L;
        return ResponseEntity.ok(researchService.recallSubmission(projectId, taskId, userId));
    }

    @PostMapping("/projects/{projectId}/tasks/{taskId}/contract-research/submissions/{submissionId}/contracts/{contractId}/review")
    @PreAuthorize("hasAnyRole('BUSINESS_DEVELOPMENT_MANAGER', 'SYSTEM_ADMIN')")
    public ResponseEntity<ContractResearchResponse> reviewContractEntry(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @PathVariable Long submissionId,
            @PathVariable String contractId,
            @Valid @RequestBody ReviewContractEntryRequest request,
            @AuthenticationPrincipal UserDetailsImpl user) {
        Long userId = user != null ? user.getId() : 1L;
        return ResponseEntity.ok(researchService.reviewContractEntry(projectId, taskId, submissionId, contractId, request, userId));
    }

    @GetMapping("/company-profiles/{companyProfileId}/contracts/research")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<List<ContractEntry>> getApprovedContracts(
            @PathVariable String companyProfileId,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        companyProfileAccessService.requireOwnerAccessibleOfficialCompanyProfile(companyProfileId, currentUser);
        return ResponseEntity.ok(researchService.getApprovedContractsForProfile(companyProfileId));
    }
}
