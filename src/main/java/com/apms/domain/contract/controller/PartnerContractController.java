//package com.apms.domain.contract.controller;
//
//import com.apms.domain.contract.dto.*;
//import com.apms.domain.contract.service.PartnerContractService;
//import com.apms.security.UserDetailsImpl;
//import lombok.RequiredArgsConstructor;
//import org.springframework.http.HttpStatus;
//import org.springframework.security.access.prepost.PreAuthorize;
//import org.springframework.security.core.annotation.AuthenticationPrincipal;
//import org.springframework.web.bind.annotation.*;
//
//import java.util.List;
//
//@RestController
//@RequestMapping("/api/v1")
//@RequiredArgsConstructor
//public class PartnerContractController {
//
//    private final PartnerContractService contractService;
//
//    @PostMapping("/projects/{projectId}/partner-contracts")
//    @ResponseStatus(HttpStatus.CREATED)
//    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAnyRole('BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF')")
//    public PartnerContractResponse createDraft(
//            @PathVariable Long projectId,
//            @RequestBody CreatePartnerContractRequest request,
//            @AuthenticationPrincipal UserDetailsImpl currentUser) {
//        return contractService.createDraft(projectId, request, currentUser.getId());
//    }
//
//    @GetMapping("/partner-contracts/{contractId}")
//    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF')")
//    public PartnerContractResponse getContract(
//            @PathVariable Long contractId,
//            @AuthenticationPrincipal UserDetailsImpl currentUser) {
//        return contractService.getContract(contractId, currentUser.getId());
//    }
//
//    @PatchMapping("/partner-contracts/{contractId}")
//    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAnyRole('BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF')")
//    public PartnerContractResponse updateContract(
//            @PathVariable Long contractId,
//            @RequestBody UpdatePartnerContractRequest request,
//            @AuthenticationPrincipal UserDetailsImpl currentUser) {
//        return contractService.updateContract(contractId, request, currentUser.getId());
//    }
//
//    @PostMapping("/partner-contracts/{contractId}/submit")
//    @ResponseStatus(HttpStatus.OK)
//    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAnyRole('BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF')")
//    public PartnerContractResponse submitForReview(
//            @PathVariable Long contractId,
//            @AuthenticationPrincipal UserDetailsImpl currentUser) {
//        return contractService.submitForReview(contractId, currentUser.getId());
//    }
//
//    @PostMapping("/partner-contracts/{contractId}/review")
//    @ResponseStatus(HttpStatus.NO_CONTENT)
//    @PreAuthorize("hasRole('BUSINESS_DEVELOPMENT_MANAGER')")
//    public void reviewContract(
//            @PathVariable Long contractId,
//            @RequestBody ReviewPartnerContractRequest request,
//            @AuthenticationPrincipal UserDetailsImpl currentUser) {
//        contractService.reviewContract(contractId, request, currentUser.getId());
//    }
//
//    @PostMapping("/partner-contracts/{contractId}/lifecycle")
//    @ResponseStatus(HttpStatus.OK)
//    @PreAuthorize("hasRole('BUSINESS_DEVELOPMENT_MANAGER')")
//    public PartnerContractResponse updateLifecycle(
//            @PathVariable Long contractId,
//            @RequestBody UpdateContractLifecycleRequest request,
//            @AuthenticationPrincipal UserDetailsImpl currentUser) {
//        return contractService.updateLifecycle(contractId, request, currentUser.getId());
//    }
//
//    @PostMapping("/partner-contracts/{contractId}/revision")
//    @ResponseStatus(HttpStatus.OK)
//    @PreAuthorize("hasRole('BUSINESS_DEVELOPMENT_MANAGER')")
//    public PartnerContractResponse startRevision(
//            @PathVariable Long contractId,
//            @AuthenticationPrincipal UserDetailsImpl currentUser) {
//        return contractService.startRevision(contractId, currentUser.getId());
//    }
//
//    @GetMapping("/partner-contracts/{contractId}/versions")
//    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF')")
//    public List<PartnerContractVersionResponse> getVersions(
//            @PathVariable Long contractId,
//            @AuthenticationPrincipal UserDetailsImpl currentUser) {
//        return contractService.getVersions(contractId, currentUser.getId());
//    }
//
//    @GetMapping("/partner-contracts/{contractId}/versions/{version}")
//    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF')")
//    public PartnerContractVersionResponse getVersion(
//            @PathVariable Long contractId,
//            @PathVariable Integer version,
//            @AuthenticationPrincipal UserDetailsImpl currentUser) {
//        return contractService.getVersion(contractId, version, currentUser.getId());
//    }
//}
