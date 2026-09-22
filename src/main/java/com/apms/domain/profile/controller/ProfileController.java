package com.apms.domain.profile.controller;

import com.apms.common.response.ApiResponse;
import com.apms.common.response.PageResponse;
import com.apms.domain.profile.dto.ProfileResponse;
import com.apms.domain.profile.dto.ProfileSourcesResponse;
import com.apms.domain.profile.dto.UpdateCompanyProfileRequest;
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
    @PreAuthorize("hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<ApiResponse<PageResponse<ProfileResponse>>> getAllProfiles(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String industry,
            @RequestParam(required = false) String market,
            @RequestParam(required = false) String reviewStatus,
            @RequestParam(required = false) String relationshipType,
            @RequestParam(required = false) com.apms.common.enums.ProfileVisibility visibility,
            @RequestParam(defaultValue = "false") boolean excludeOwner,
            @RequestParam(required = false) Boolean createdByMe,
            @RequestParam(required = false) Boolean managedByMe,
            @RequestParam(required = false) Boolean officialOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        Set<String> allowedCompanyIds = companyScope.allowedCompanyIds();
        
        String effectiveReviewStatus = reviewStatus;
        com.apms.common.enums.ProfileVisibility effectiveVisibility;
        if (Boolean.TRUE.equals(officialOnly)) {
            effectiveVisibility = visibility;
        } else {
            effectiveVisibility = (visibility != null) ? visibility : com.apms.common.enums.ProfileVisibility.PUBLISHED;
        }

        Long managedByManagerId = Boolean.TRUE.equals(managedByMe) ? currentUser.getId() : null;
        Long managerId = (managedByManagerId == null && Boolean.TRUE.equals(createdByMe)) ? currentUser.getId() : null;

        PageResponse<ProfileResponse> response = PageResponse.of(
                profileService.searchCompanyProfiles(keyword, industry, market, effectiveReviewStatus, relationshipType, excludeOwner, allowedCompanyIds, effectiveVisibility, managerId, managedByManagerId, officialOnly, PageRequest.of(page, size)));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // ─────────────────────────────────────────────
    // GET /api/v1/company-profiles/industries (or /profiles/industries)
    // Role: BUSINESS_OWNER, BUSINESS_DEVELOPMENT_MANAGER
    // ─────────────────────────────────────────────
    @GetMapping("/industries")
    @PreAuthorize("hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<ApiResponse<java.util.List<String>>> getDistinctIndustries() {
        return ResponseEntity.ok(ApiResponse.success(profileService.getDistinctIndustries(), "Distinct industries retrieved"));
    }

    // ─────────────────────────────────────────────
    // GET /api/v1/company-profiles/relationship-types (or /profiles/relationship-types)
    // Role: BUSINESS_OWNER, BUSINESS_DEVELOPMENT_MANAGER
    // ─────────────────────────────────────────────
    @GetMapping("/relationship-types")
    @PreAuthorize("hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<ApiResponse<java.util.List<String>>> getDistinctRelationshipTypes(
            @RequestParam(defaultValue = "true") boolean excludeOwner,
            @RequestParam(required = false) Boolean createdByMe,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        Long managerId = (createdByMe != null && createdByMe && currentUser != null) ? currentUser.getId() : null;
        return ResponseEntity.ok(ApiResponse.success(profileService.getDistinctRelationshipTypes(excludeOwner, managerId), "Distinct relationship types retrieved"));
    }

    // ─────────────────────────────────────────────
    // GET /api/v1/profiles/{companyId}
    // Role: BUSINESS_OWNER, BUSINESS_DEVELOPMENT_MANAGER, BUSINESS_DEVELOPMENT_STAFF (project-scoped)
    // ─────────────────────────────────────────────
    @GetMapping("/{companyId}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER') or (hasRole('BUSINESS_DEVELOPMENT_STAFF') and #projectId != null and @companyScope.canReadCompanyProfileFromProject(principal.id, #projectId, #companyId))")
    public ResponseEntity<ApiResponse<ProfileResponse>> getProfile(
            @PathVariable String companyId,
            @RequestParam(required = false) Long projectId) {

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
                profileService.searchBusinessFacingProfilesByName(name, excludeOwner, PageRequest.of(page, size)));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // ─────────────────────────────────────────────
    // GET /api/v1/profiles/{companyId}/sources
    // Role: BUSINESS_OWNER, BUSINESS_DEVELOPMENT_MANAGER, BUSINESS_DEVELOPMENT_STAFF (project-scoped)
    // ─────────────────────────────────────────────
    @GetMapping("/{companyId}/sources")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER') or (hasRole('BUSINESS_DEVELOPMENT_STAFF') and #projectId != null and @companyScope.canReadCompanyProfileFromProject(principal.id, #projectId, #companyId))")
    public ResponseEntity<ApiResponse<ProfileSourcesResponse>> getProfileSources(
            @PathVariable String companyId,
            @RequestParam(required = false) Long projectId) {

        return ResponseEntity.ok(ApiResponse.success(profileService.getProfileSources(companyId)));
    }
    // ─────────────────────────────────────────────
    // PATCH /api/v1/company-profiles/{companyId}
    // Role: SYSTEM_ADMIN, BUSINESS_DEVELOPMENT_MANAGER
    // ─────────────────────────────────────────────
    @PatchMapping("/{companyId}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or (hasRole('BUSINESS_DEVELOPMENT_MANAGER') and @companyScope.canManageCompanyProfile(#companyId))")
    public ResponseEntity<ApiResponse<ProfileResponse>> updateProfile(
            @PathVariable String companyId,
            @RequestBody UpdateCompanyProfileRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        return ResponseEntity.ok(ApiResponse.success(profileService.updateProfile(companyId, request, currentUser), "Profile updated"));
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
    // PATCH /api/v1/company-profiles/{companyProfileId}/responsible-manager
    // Role: SYSTEM_ADMIN, BUSINESS_OWNER, BUSINESS_DEVELOPMENT_MANAGER
    // ─────────────────────────────────────────────
    @PatchMapping("/{companyProfileId}/responsible-manager")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<ApiResponse<Void>> transferResponsibility(
            @PathVariable String companyProfileId,
            @jakarta.validation.Valid @RequestBody com.apms.domain.profile.dto.TransferResponsibilityRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        profileService.transferResponsibility(companyProfileId, request, currentUser);
        return ResponseEntity.ok(ApiResponse.success(null, "Responsibility transferred successfully"));
    }

    // ─────────────────────────────────────────────
    // GET /api/v1/company-profiles/{companyProfileId}/management-history
    // Role: SYSTEM_ADMIN, BUSINESS_OWNER, BUSINESS_DEVELOPMENT_MANAGER
    // ─────────────────────────────────────────────
    @GetMapping("/{companyProfileId}/management-history")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<ApiResponse<java.util.List<com.apms.domain.profile.dto.CompanyProfileManagerHistoryDto>>> getManagementHistory(
            @PathVariable String companyProfileId,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        return ResponseEntity.ok(ApiResponse.success(profileService.getManagementHistory(companyProfileId, currentUser)));
    }

    // ─────────────────────────────────────────────
    // GET /api/v1/company-profiles/{companyProfileId}/eligible-managers
    // Role: SYSTEM_ADMIN, BUSINESS_OWNER, BUSINESS_DEVELOPMENT_MANAGER
    // ─────────────────────────────────────────────
    @GetMapping("/{companyProfileId}/eligible-managers")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<ApiResponse<java.util.List<com.apms.domain.profile.dto.EligibleManagerDto>>> getEligibleManagers(
            @PathVariable String companyProfileId,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        return ResponseEntity.ok(ApiResponse.success(profileService.getEligibleManagers(companyProfileId, currentUser)));
    }

    // ─────────────────────────────────────────────
    // PATCH /api/v1/company-profiles/{companyId}/visibility (or /api/v1/profiles/{companyId}/visibility)
    // Role: SYSTEM_ADMIN, BUSINESS_DEVELOPMENT_MANAGER (must be responsible manager)
    // ─────────────────────────────────────────────
    @PatchMapping("/{companyId}/visibility")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or (hasRole('BUSINESS_DEVELOPMENT_MANAGER') and @companyScope.canManageCompanyProfile(#companyId))")
    public ResponseEntity<ApiResponse<ProfileResponse>> updateVisibility(
            @PathVariable String companyId,
            @jakarta.validation.Valid @RequestBody com.apms.domain.profile.dto.UpdateProfileVisibilityRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        Long actorId = currentUser != null ? currentUser.getId() : null;
        return ResponseEntity.ok(ApiResponse.success(profileService.updateVisibility(companyId, request, actorId)));
    }

    // ─────────────────────────────────────────────
    // GET /api/v1/profiles/visibility-management
    // Role: SYSTEM_ADMIN, BUSINESS_OWNER, BUSINESS_DEVELOPMENT_MANAGER
    // ─────────────────────────────────────────────
    @GetMapping("/visibility-management")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<ApiResponse<PageResponse<ProfileResponse>>> getVisibilityManagementProfiles(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) com.apms.common.enums.ProfileVisibility visibility,
            @RequestParam(required = false) String eligibility,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        boolean isAdmin = currentUser != null && currentUser.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_SYSTEM_ADMIN") || a.getAuthority().equals("ROLE_BUSINESS_OWNER"));
        Long managerId = currentUser != null ? currentUser.getId() : null;

        return ResponseEntity.ok(ApiResponse.success(PageResponse.of(
                profileService.getVisibilityManagementProfiles(
                        keyword, visibility, eligibility, managerId, isAdmin, PageRequest.of(page, size))
        )));
    }

    // ─────────────────────────────────────────────
    // GET /api/v1/profiles/visibility-management/summary
    // Role: SYSTEM_ADMIN, BUSINESS_OWNER, BUSINESS_DEVELOPMENT_MANAGER
    // ─────────────────────────────────────────────
    @GetMapping("/visibility-management/summary")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<ApiResponse<com.apms.domain.profile.dto.ProfileVisibilitySummaryDto>> getVisibilityManagementSummary(
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        boolean isAdmin = currentUser != null && currentUser.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_SYSTEM_ADMIN") || a.getAuthority().equals("ROLE_BUSINESS_OWNER"));
        Long managerId = currentUser != null ? currentUser.getId() : null;

        return ResponseEntity.ok(ApiResponse.success(
                profileService.getVisibilitySummary(managerId, isAdmin)
        ));
    }
}
