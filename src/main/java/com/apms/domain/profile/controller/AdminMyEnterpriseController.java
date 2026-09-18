package com.apms.domain.profile.controller;

import com.apms.common.response.ApiResponse;
import com.apms.domain.profile.dto.AdminUpdateEnterpriseBasicInfoRequest;
import com.apms.domain.profile.dto.AdminUpdateEnterpriseBusinessFieldsRequest;
import com.apms.domain.profile.dto.AdminUpdateEnterpriseLeadershipRequest;
import com.apms.domain.profile.dto.ProfileResponse;
import com.apms.domain.profile.service.OwnerOrganizationService;
import com.apms.domain.profile.service.ProfileService;
import com.apms.security.UserDetailsImpl;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller for SYSTEM_ADMIN to view and manage basic enterprise information,
 * business fields, and leadership of the canonical Owner Enterprise (FPT Corporation).
 * Strictly anti-IDOR: backend resolves the canonical owner enterprise itself;
 * clients cannot supply a company ID.
 */
@RestController
@RequestMapping("/api/v1/admin/my-enterprise")
@RequiredArgsConstructor
public class AdminMyEnterpriseController {

    private final OwnerOrganizationService ownerOrganizationService;
    private final ProfileService profileService;

    @GetMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<ProfileResponse>> getAdminMyEnterprise() {
        String ownerId = ownerOrganizationService.getOwnerCompanyId();
        return ResponseEntity.ok(ApiResponse.success(profileService.getProfileByCompanyId(ownerId)));
    }

    @PatchMapping("/basic-info")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<ProfileResponse>> updateAdminEnterpriseBasicInfo(
            @Valid @RequestBody AdminUpdateEnterpriseBasicInfoRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        ProfileResponse response = profileService.updateAdminEnterpriseBasicInfo(request, currentUser);
        return ResponseEntity.ok(ApiResponse.success(response, "Enterprise basic information updated successfully"));
    }

    @PatchMapping("/business-fields")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<ProfileResponse>> updateAdminEnterpriseBusinessFields(
            @Valid @RequestBody AdminUpdateEnterpriseBusinessFieldsRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        ProfileResponse response = profileService.updateAdminEnterpriseBusinessFields(request, currentUser);
        return ResponseEntity.ok(ApiResponse.success(response, "Enterprise business fields updated successfully"));
    }

    @PutMapping("/leadership")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<ProfileResponse>> updateAdminEnterpriseLeadership(
            @Valid @RequestBody AdminUpdateEnterpriseLeadershipRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        ProfileResponse response = profileService.updateAdminEnterpriseLeadership(request, currentUser);
        return ResponseEntity.ok(ApiResponse.success(response, "Enterprise leadership updated successfully"));
    }
}
