package com.apms.domain.profile.controller;

import com.apms.common.response.ApiResponse;
import com.apms.domain.profile.CompanyProfileFinancialReport;
import com.apms.domain.profile.dto.ProfileResponse;
import com.apms.domain.profile.dto.UpdateAdminOwnerProfileRequest;
import com.apms.domain.profile.service.OwnerOrganizationService;
import com.apms.domain.profile.service.ProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

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
        ProfileResponse response = profileService.getProfileByCompanyId(ownerId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PatchMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<ProfileResponse>> updateOwnerCompanyProfile(
            @RequestBody UpdateAdminOwnerProfileRequest request) {
        String ownerId = ownerOrganizationService.getOwnerCompanyId();
        ProfileResponse response = profileService.updateOwnerProfile(ownerId, request);
        return ResponseEntity.ok(ApiResponse.success(response, "Owner profile updated successfully"));
    }

    @PutMapping("/financials")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<ProfileResponse>> upsertOwnerFinancialReport(
            @RequestBody CompanyProfileFinancialReport report) {
        String ownerId = ownerOrganizationService.getOwnerCompanyId();
        ProfileResponse response = profileService.upsertOwnerFinancialReport(ownerId, report);
        return ResponseEntity.ok(ApiResponse.success(response, "Financial report saved successfully"));
    }

    @DeleteMapping("/financials/{reportType}/{reportYear}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<ProfileResponse>> deleteOwnerFinancialReport(
            @PathVariable String reportType,
            @PathVariable Integer reportYear) {
        String ownerId = ownerOrganizationService.getOwnerCompanyId();
        ProfileResponse response = profileService.deleteOwnerFinancialReport(ownerId, reportType, reportYear);
        return ResponseEntity.ok(ApiResponse.success(response, "Financial report deleted successfully"));
    }
}
