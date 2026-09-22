//package com.apms.domain.contract.controller;
//
//import com.apms.common.response.ApiResponse;
//import com.apms.domain.contract.dto.PartnerContractResponse;
//import com.apms.domain.contract.service.PartnerContractService;
//import com.apms.security.UserDetailsImpl;
//import lombok.RequiredArgsConstructor;
//import org.springframework.security.access.prepost.PreAuthorize;
//import org.springframework.security.core.annotation.AuthenticationPrincipal;
//import org.springframework.web.bind.annotation.*;
//
//import java.util.List;
//
//@RestController
//@RequestMapping("/api/v1/company-profiles/{profileId}/partner-contracts")
//@RequiredArgsConstructor
//public class PartnerContractProfileController {
//
//    private final PartnerContractService partnerContractService;
//
//    @GetMapping
//    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF')")
//    public ApiResponse<com.apms.common.response.PageResponse<PartnerContractResponse>> listContracts(
//            @PathVariable String profileId,
//            @RequestParam(defaultValue = "0") int page,
//            @RequestParam(defaultValue = "10") int size,
//            @RequestParam(defaultValue = "createdAt,desc") String[] sort,
//            @AuthenticationPrincipal UserDetailsImpl currentUser) {
//
//        if (size > 100) {
//            size = 100;
//        }
//
//        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(
//            page, size, org.springframework.data.domain.Sort.by(
//                org.springframework.data.domain.Sort.Direction.fromString(sort[1].toUpperCase()), sort[0]
//            )
//        );
//
//        return ApiResponse.success(partnerContractService.listContractsByProfile(profileId, currentUser.getId(), pageable));
//    }
//
//    @GetMapping("/summary")
//    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF')")
//    public ApiResponse<com.apms.domain.contract.dto.PartnerContractSummaryResponse> getContractSummary(
//            @PathVariable String profileId,
//            @AuthenticationPrincipal UserDetailsImpl currentUser) {
//        return ApiResponse.success(partnerContractService.getContractSummaryByProfile(profileId, currentUser.getId()));
//    }
//
//    @GetMapping("/{contractId}")
//    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF')")
//    public ApiResponse<PartnerContractResponse> getContract(
//            @PathVariable String profileId,
//            @PathVariable Long contractId,
//            @AuthenticationPrincipal UserDetailsImpl currentUser) {
//        return ApiResponse.success(partnerContractService.getContractByProfile(profileId, contractId, currentUser.getId()));
//    }
//}
