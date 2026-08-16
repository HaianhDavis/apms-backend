package com.apms.domain.profile.controller;

import com.apms.common.response.ApiResponse;
import com.apms.common.response.PageResponse;
import com.apms.domain.profile.dto.ProfileResponse;
import com.apms.domain.profile.dto.ProfileSourcesResponse;
import com.apms.domain.profile.service.ProfileService;
import com.apms.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

@RestController
@RequestMapping(path = {"/api/v1/profiles", "/api/v1/company-profiles"})
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileService profileService;
    private final com.apms.common.security.StaffCompanyScopeEvaluator companyScope;

    // ─────────────────────────────────────────────
    // GET /api/v1/company-profiles (or /profiles)
    // Role: BUSINESS_OWNER, BUSINESS_DEVELOPMENT_MANAGER, BUSINESS_DEVELOPMENT_STAFF
    // ─────────────────────────────────────────────
    @GetMapping
    @PreAuthorize("hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF')")
    public ResponseEntity<ApiResponse<PageResponse<ProfileResponse>>> getAllProfiles(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String industry,
            @RequestParam(required = false) String market,
            @RequestParam(required = false) String reviewStatus,
            @RequestParam(required = false) String relationshipType,
            @RequestParam(defaultValue = "false") boolean excludeOwner,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        Set<String> allowedCompanyIds = companyScope.allowedCompanyIds();
        PageResponse<ProfileResponse> response = PageResponse.of(
                profileService.searchCompanyProfiles(keyword, industry, market, reviewStatus, relationshipType, excludeOwner, PageRequest.of(page, size)));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // ─────────────────────────────────────────────
    // GET /api/v1/profiles/{companyId}
    // Role: BUSINESS_OWNER, BUSINESS_DEVELOPMENT_MANAGER, BUSINESS_DEVELOPMENT_STAFF
    // ─────────────────────────────────────────────
    @GetMapping("/{companyId}")
    @PreAuthorize("hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF') and @companyScope.canAccessCompany(#companyId)")
    public ResponseEntity<ApiResponse<ProfileResponse>> getProfile(
            @PathVariable String companyId) {

        return ResponseEntity.ok(ApiResponse.success(profileService.getProfileByCompanyId(companyId)));
    }

    @GetMapping("/exists")
    @PreAuthorize("hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF')")
    public ResponseEntity<ApiResponse<Boolean>> checkDuplicateTaxCode(
            @RequestParam String taxCode) {
        boolean exists = profileService.checkDuplicateByTaxCode(taxCode);
        return ResponseEntity.ok(ApiResponse.success(exists));
    }

    // ─────────────────────────────────────────────
    // GET /api/v1/profiles/search?name=
    // Role: BUSINESS_OWNER, BUSINESS_DEVELOPMENT_MANAGER, BUSINESS_DEVELOPMENT_STAFF
    // ─────────────────────────────────────────────
    @GetMapping("/search")
    @PreAuthorize("hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF')")
    public ResponseEntity<ApiResponse<PageResponse<ProfileResponse>>> searchProfiles(
            @RequestParam String name,
            @RequestParam(defaultValue = "false") boolean excludeOwner,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        PageResponse<ProfileResponse> response = PageResponse.of(
                profileService.searchProfilesByName(name, excludeOwner, PageRequest.of(page, size)));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // ─────────────────────────────────────────────
    // GET /api/v1/profiles/{companyId}/sources
    // Role: BUSINESS_OWNER, BUSINESS_DEVELOPMENT_MANAGER, BUSINESS_DEVELOPMENT_STAFF
    // ─────────────────────────────────────────────
    @GetMapping("/{companyId}/sources")
    @PreAuthorize("hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF') and @companyScope.canAccessCompany(#companyId)")
    public ResponseEntity<ApiResponse<ProfileSourcesResponse>> getProfileSources(
            @PathVariable String companyId) {

        return ResponseEntity.ok(ApiResponse.success(profileService.getProfileSources(companyId)));
    }
    // ─────────────────────────────────────────────
    // PATCH /api/v1/company-profiles/{companyId}
    // Role: SYSTEM_ADMIN, BUSINESS_DEVELOPMENT_MANAGER
    // ─────────────────────────────────────────────
    @PatchMapping("/{companyId}")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<ApiResponse<ProfileResponse>> updateProfile(
            @PathVariable String companyId,
            @RequestBody com.apms.domain.profile.dto.UpdateCompanyProfileRequest request) {

        return ResponseEntity.ok(ApiResponse.success(profileService.updateProfile(companyId, request), "Profile updated"));
    }

    // ─────────────────────────────────────────────
    // DELETE /api/v1/company-profiles/{companyId}
    // Role: SYSTEM_ADMIN
    // ─────────────────────────────────────────────
    @DeleteMapping("/{companyId}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteProfile(
            @PathVariable String companyId) {

        profileService.deleteProfile(companyId);
        return ResponseEntity.ok(ApiResponse.success(null, "Profile deleted"));
    }

    // ─────────────────────────────────────────────
    // PUT /api/v1/company-profiles/{companyId}/responsible-manager
    // Role: SYSTEM_ADMIN, BUSINESS_DEVELOPMENT_MANAGER
    // ─────────────────────────────────────────────
    @PutMapping("/{companyId}/responsible-manager")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<ApiResponse<Void>> transferResponsibility(
            @PathVariable String companyId,
            @jakarta.validation.Valid @RequestBody com.apms.domain.profile.dto.TransferResponsibilityRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        profileService.transferResponsibility(companyId, request.getManagerId(), currentUser);
        return ResponseEntity.ok(ApiResponse.success(null, "Responsibility transferred successfully"));
    }
}
