package com.apms.domain.profile.controller;

import com.apms.common.response.ApiResponse;
import com.apms.common.response.PageResponse;
import com.apms.domain.profile.dto.ProfileResponse;
import com.apms.domain.profile.dto.ProfileSourcesResponse;
import com.apms.domain.profile.service.ProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/profiles")
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileService profileService;

    // ─────────────────────────────────────────────
    // GET /api/v1/profiles
    // Role: BUSINESS_OWNER, BUSINESS_DEVELOPMENT_MANAGER, RESEARCH_STAFF
    // ─────────────────────────────────────────────
    @GetMapping
    @PreAuthorize("hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER', 'RESEARCH_STAFF')")
    public ResponseEntity<ApiResponse<PageResponse<ProfileResponse>>> getAllProfiles(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        PageResponse<ProfileResponse> response = PageResponse.of(
                profileService.getAllProfiles(PageRequest.of(page, size)));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // ─────────────────────────────────────────────
    // GET /api/v1/profiles/{companyId}
    // Role: BUSINESS_OWNER, BUSINESS_DEVELOPMENT_MANAGER, RESEARCH_STAFF
    // ─────────────────────────────────────────────
    @GetMapping("/{companyId}")
    @PreAuthorize("hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER', 'RESEARCH_STAFF')")
    public ResponseEntity<ApiResponse<ProfileResponse>> getProfile(
            @PathVariable String companyId) {

        return ResponseEntity.ok(ApiResponse.success(profileService.getProfileByCompanyId(companyId)));
    }

    // ─────────────────────────────────────────────
    // GET /api/v1/profiles/search?name=
    // Role: BUSINESS_OWNER, BUSINESS_DEVELOPMENT_MANAGER, RESEARCH_STAFF
    // ─────────────────────────────────────────────
    @GetMapping("/search")
    @PreAuthorize("hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER', 'RESEARCH_STAFF')")
    public ResponseEntity<ApiResponse<PageResponse<ProfileResponse>>> searchProfiles(
            @RequestParam String name,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        PageResponse<ProfileResponse> response = PageResponse.of(
                profileService.searchProfilesByName(name, PageRequest.of(page, size)));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // ─────────────────────────────────────────────
    // GET /api/v1/profiles/{companyId}/sources
    // Role: BUSINESS_OWNER, BUSINESS_DEVELOPMENT_MANAGER, RESEARCH_STAFF
    // ─────────────────────────────────────────────
    @GetMapping("/{companyId}/sources")
    @PreAuthorize("hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER', 'RESEARCH_STAFF')")
    public ResponseEntity<ApiResponse<ProfileSourcesResponse>> getProfileSources(
            @PathVariable String companyId) {

        return ResponseEntity.ok(ApiResponse.success(profileService.getProfileSources(companyId)));
    }
}
