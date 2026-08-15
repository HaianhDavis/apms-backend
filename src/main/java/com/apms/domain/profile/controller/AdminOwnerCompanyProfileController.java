package com.apms.domain.profile.controller;

import com.apms.common.response.ApiResponse;
import com.apms.domain.profile.dto.FinancialReportRequest;
import com.apms.domain.profile.dto.ProfileResponse;
import com.apms.domain.profile.dto.UpdateOwnerCompanyProfileRequest;
import com.apms.domain.profile.service.OwnerOrganizationService;
import com.apms.domain.profile.service.ProfileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SYSTEM_ADMIN management of the Owner Organization's CompanyProfile.
 * The Owner Company is resolved by the backend from configuration;
 * clients never supply a companyId.
 */
@RestController
@RequestMapping("/api/v1/admin/owner-company-profile")
@RequiredArgsConstructor
public class AdminOwnerCompanyProfileController {

    private final OwnerOrganizationService ownerOrganizationService;
    private final ProfileService profileService;

    @GetMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<ProfileResponse>> getOwnerCompanyProfile() {
        String ownerId = ownerOrganizationService.getOwnerCompanyId();
        return ResponseEntity.ok(ApiResponse.success(profileService.getProfileByCompanyId(ownerId)));
    }

    @PatchMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<ProfileResponse>> updateOwnerCompanyProfile(
            @Valid @RequestBody UpdateOwnerCompanyProfileRequest request) {
        return ResponseEntity.ok(ApiResponse.success(profileService.updateOwnerCompanyProfile(request)));
    }

    /**
     * SYSTEM_ADMIN upserts one financial statement (reportType + reportYear) of the
     * Owner Organization. The Owner Company is resolved by the backend; the client
     * never supplies a companyId (anti-IDOR).
     */
    @PutMapping("/financials")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<ProfileResponse>> upsertOwnerFinancialReport(
            @Valid @RequestBody FinancialReportRequest request) {
        return ResponseEntity.ok(ApiResponse.success(profileService.upsertOwnerFinancialReport(request)));
    }

    /**
     * SYSTEM_ADMIN deletes one financial statement (reportType + reportYear) of the
     * Owner Organization. The Owner Company is resolved by the backend (anti-IDOR).
     */
    @DeleteMapping("/financials/{reportType}/{reportYear}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<ProfileResponse>> deleteOwnerFinancialReport(
            @PathVariable String reportType,
            @PathVariable int reportYear) {
        return ResponseEntity.ok(ApiResponse.success(profileService.deleteOwnerFinancialReport(reportType, reportYear)));
    }
}
