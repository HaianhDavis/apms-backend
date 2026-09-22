package com.apms.domain.profile.controller;

import com.apms.common.response.ApiResponse;
import com.apms.common.security.StaffCompanyScopeEvaluator;
import com.apms.domain.profile.dto.BatchUpdateCompanyContractsRequest;
import com.apms.domain.profile.dto.CompanyProfileContractDto;
import com.apms.domain.profile.dto.UpdateCompanyProfileContractRequest;
import com.apms.domain.profile.service.CompanyProfileContractService;
import com.apms.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping(path = {"/api/v1/company-profiles", "/api/v1/profiles"})
@RequiredArgsConstructor
public class CompanyProfileContractController {

    private final CompanyProfileContractService contractService;
    private final StaffCompanyScopeEvaluator companyScope;

    /**
     * Strictly read-only query for canonical company profile contracts.
     * Does NOT mutate database state or run implicit backfill.
     */
    @GetMapping("/{companyProfileId}/contracts")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER') or (hasRole('BUSINESS_DEVELOPMENT_STAFF') and #projectId != null and @companyScope.canReadCompanyProfileFromProject(principal.id, #projectId, #companyProfileId))")
    public ResponseEntity<ApiResponse<List<CompanyProfileContractDto>>> getContracts(
            @PathVariable String companyProfileId,
            @RequestParam(required = false) Long projectId) {
        List<CompanyProfileContractDto> contracts = contractService.getContractsForProfile(companyProfileId);
        return ResponseEntity.ok(ApiResponse.success(contracts));
    }

    /**
     * Update a single canonical company profile contract.
     * Recomputes derivedContractStatus strictly from dates.
     * Keeps contractType and provenance immutable.
     */
    @PutMapping("/{companyProfileId}/contracts/{contractId}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or (hasRole('BUSINESS_DEVELOPMENT_MANAGER') and @companyScope.canManageCompanyProfile(#companyProfileId))")
    public ResponseEntity<ApiResponse<CompanyProfileContractDto>> updateContract(
            @PathVariable String companyProfileId,
            @PathVariable String contractId,
            @RequestBody UpdateCompanyProfileContractRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        Long userId = currentUser != null ? currentUser.getId() : null;
        CompanyProfileContractDto updated = contractService.updateContract(companyProfileId, contractId, request, userId);
        return ResponseEntity.ok(ApiResponse.success(updated));
    }

    /**
     * Batch update multiple canonical contracts for a company profile.
     */
    @PutMapping("/{companyProfileId}/contracts")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or (hasRole('BUSINESS_DEVELOPMENT_MANAGER') and @companyScope.canManageCompanyProfile(#companyProfileId))")
    public ResponseEntity<ApiResponse<List<CompanyProfileContractDto>>> batchUpdateContracts(
            @PathVariable String companyProfileId,
            @RequestBody BatchUpdateCompanyContractsRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        Long userId = currentUser != null ? currentUser.getId() : null;
        List<CompanyProfileContractDto> updated = contractService.batchUpdateContracts(companyProfileId, request, userId);
        return ResponseEntity.ok(ApiResponse.success(updated));
    }

    /**
     * Explicit idempotent backfill endpoint to migrate existing approved ContractResearch into canonical contracts.
     * Scoped strictly to the target companyProfileId.
     */
    @PostMapping("/{companyProfileId}/contracts/backfill")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or (hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER') and @companyScope.canAccessCompany(#companyProfileId))")
    public ResponseEntity<ApiResponse<Integer>> backfillContracts(
            @PathVariable String companyProfileId,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        Long userId = currentUser != null ? currentUser.getId() : null;
        int backfilledCount = contractService.backfillApprovedResearchesForProfile(companyProfileId, userId);
        return ResponseEntity.ok(ApiResponse.success(backfilledCount));
    }
}
