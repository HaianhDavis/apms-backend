package com.apms.domain.profile.controller;

import com.apms.common.response.ApiResponse;
import com.apms.domain.profile.dto.BatchUpdateCompanyFinancialsRequest;
import com.apms.domain.profile.dto.CompanyProfileFinancialRowDto;
import com.apms.domain.profile.service.CompanyProfileFinancialService;
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
public class CompanyProfileFinancialController {

    private final CompanyProfileFinancialService financialService;
    private final com.apms.common.security.StaffCompanyScopeEvaluator companyScope;

    /**
     * Strictly read-only query for canonical company profile financial rows.
     * Does NOT mutate database state.
     */
    @GetMapping("/{companyProfileId}/financials")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER') or (hasRole('BUSINESS_DEVELOPMENT_STAFF') and #projectId != null and @companyScope.canReadCompanyProfileFromProject(principal.id, #projectId, #companyProfileId))")
    public ResponseEntity<ApiResponse<List<CompanyProfileFinancialRowDto>>> getFinancials(
            @PathVariable String companyProfileId,
            @RequestParam(required = false) Long projectId) {
        List<CompanyProfileFinancialRowDto> rows = financialService.getFinancials(companyProfileId);
        return ResponseEntity.ok(ApiResponse.success(rows));
    }

    /**
     * Batch update/create/delete canonical company profile financial rows.
     * Enforces row ownership per ID and preserves research provenance.
     */
    @PutMapping("/{companyProfileId}/financials")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or (hasRole('BUSINESS_DEVELOPMENT_MANAGER') and @companyScope.canManageCompanyProfile(#companyProfileId))")
    public ResponseEntity<ApiResponse<List<CompanyProfileFinancialRowDto>>> updateFinancials(
            @PathVariable String companyProfileId,
            @RequestBody BatchUpdateCompanyFinancialsRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        List<CompanyProfileFinancialRowDto> rows = financialService.batchUpdateFinancials(companyProfileId, request, currentUser);
        return ResponseEntity.ok(ApiResponse.success(rows));
    }

    /**
     * Explicit idempotent backfill endpoint to migrate existing approved FinancialResearch into canonical rows.
     */
    @PostMapping("/{companyProfileId}/financials/backfill")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or (hasRole('BUSINESS_DEVELOPMENT_MANAGER') and @companyScope.canManageCompanyProfile(#companyProfileId))")
    public ResponseEntity<ApiResponse<Integer>> backfillFinancials(
            @PathVariable String companyProfileId) {
        int backfilledCount = financialService.backfillExistingApprovedResearch(companyProfileId);
        return ResponseEntity.ok(ApiResponse.success(backfilledCount));
    }
}
