package com.apms.domain.profile.controller;

import com.apms.common.response.ApiResponse;
import com.apms.common.response.PageResponse;
import com.apms.domain.profile.dto.CompanyProfileVersionResponse;
import com.apms.domain.profile.dto.OwnerProfileReadinessResponse;
import com.apms.domain.profile.dto.ProfileResponse;
import com.apms.domain.profile.dto.ReferenceCompanyContextResponse;
import com.apms.domain.profile.service.CompanyProfileVersionService;
import com.apms.domain.profile.service.OwnerOrganizationService;
import com.apms.domain.profile.service.ProfileService;
import com.apms.domain.profile.service.ReferenceCompanyContextService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/owner")
@RequiredArgsConstructor
public class OwnerCompanyProfileController {

    private final OwnerOrganizationService ownerOrganizationService;
    private final ProfileService profileService;
    private final CompanyProfileVersionService versionService;
    private final ReferenceCompanyContextService referenceCompanyContextService;

    // ─────────────────────────────────────────────
    // GET /api/v1/owner/company-profile
    // Role: SYSTEM_ADMIN, BUSINESS_OWNER, BUSINESS_DEVELOPMENT_MANAGER, BUSINESS_DEVELOPMENT_STAFF
    // ─────────────────────────────────────────────
    @GetMapping("/company-profile")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF')")
    public ResponseEntity<ApiResponse<ProfileResponse>> getOwnerCompanyProfile() {
        com.apms.domain.profile.CompanyProfile ownerProfile = ownerOrganizationService.resolveApprovedOwnerProfile();
        ProfileResponse response = profileService.toResponse(ownerProfile);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // ─────────────────────────────────────────────
    // GET /api/v1/owner/company-profile/readiness
    // Role: SYSTEM_ADMIN, BUSINESS_OWNER, BUSINESS_DEVELOPMENT_MANAGER, BUSINESS_DEVELOPMENT_STAFF
    // ─────────────────────────────────────────────
    @GetMapping("/company-profile/readiness")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF')")
    public ResponseEntity<ApiResponse<OwnerProfileReadinessResponse>> getOwnerCompanyProfileReadiness() {
        OwnerProfileReadinessResponse response = ownerOrganizationService.checkReadiness();
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // ─────────────────────────────────────────────
    // GET /api/v1/owner/company-profile/versions
    // Role: SYSTEM_ADMIN, BUSINESS_OWNER, BUSINESS_DEVELOPMENT_MANAGER, BUSINESS_DEVELOPMENT_STAFF
    // ─────────────────────────────────────────────
//    @GetMapping("/company-profile/versions")
//    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF')")
    public ResponseEntity<PageResponse<CompanyProfileVersionResponse>> getOwnerVersions(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "10") int size
    ) {
        String ownerId = ownerOrganizationService.getOwnerCompanyId();
        Pageable pageable = PageRequest.of(page, size);
        Page<CompanyProfileVersionResponse> pageResult = versionService.getVersions(ownerId, pageable);

        PageResponse<CompanyProfileVersionResponse> response = new PageResponse<>(
                pageResult.getContent(),
                pageResult.getNumber(),
                pageResult.getSize(),
                pageResult.getTotalElements(),
                pageResult.getTotalPages(),
                pageResult.isLast()
        );
        return ResponseEntity.ok(response);
    }

    // ─────────────────────────────────────────────
    // GET /api/v1/owner/company-intelligence/{id}
    // Role: SYSTEM_ADMIN, BUSINESS_OWNER, BUSINESS_DEVELOPMENT_MANAGER, BUSINESS_DEVELOPMENT_STAFF
    // ─────────────────────────────────────────────
    @GetMapping("/company-intelligence/{id}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF')")
    public ResponseEntity<ApiResponse<Object>> getCompanyIntelligence(@PathVariable String id) {
        // Mock empty response to prevent 500 errors on frontend
        return ResponseEntity.ok(ApiResponse.success(null));
    }


    // ─────────────────────────────────────────────
    // GET /api/v1/owner/company-profile/versions/{version}
    // Role: SYSTEM_ADMIN, BUSINESS_OWNER, BUSINESS_DEVELOPMENT_MANAGER, BUSINESS_DEVELOPMENT_STAFF
    // ─────────────────────────────────────────────
    @GetMapping("/company-profile/versions/{version}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF')")
    public ResponseEntity<CompanyProfileVersionResponse> getOwnerVersion(
            @PathVariable String version) {
        String ownerId = ownerOrganizationService.getOwnerCompanyId();
        return ResponseEntity.ok(versionService.getVersion(ownerId, version));
    }

    // ─────────────────────────────────────────────
    // GET /api/v1/owner/reference-context
    // Role: SYSTEM_ADMIN, BUSINESS_OWNER, BUSINESS_DEVELOPMENT_MANAGER, BUSINESS_DEVELOPMENT_STAFF
    // ─────────────────────────────────────────────
    @GetMapping("/reference-context")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF')")
    public ResponseEntity<ApiResponse<ReferenceCompanyContextResponse>> getReferenceContext() {
        ReferenceCompanyContextResponse response = referenceCompanyContextService.getReferenceCompanyContext();
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
