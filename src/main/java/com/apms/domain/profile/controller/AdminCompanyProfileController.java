package com.apms.domain.profile.controller;

import com.apms.common.response.ApiResponse;
import com.apms.common.response.PageResponse;
import com.apms.domain.profile.dto.ProfileResponse;
import com.apms.domain.profile.service.ProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/company-profiles")
@RequiredArgsConstructor
public class AdminCompanyProfileController {

    private final ProfileService profileService;

    @GetMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<PageResponse<ProfileResponse>>> getCompanyProfiles(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<ProfileResponse> resultPage = profileService.getCompanyProfilesForAdmin(status, keyword, page, size);
        return ResponseEntity.ok(ApiResponse.success(PageResponse.of(resultPage)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<ProfileResponse>> getCompanyProfile(
            @PathVariable String id) {

        ProfileResponse response = profileService.getCompanyProfileForAdmin(id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PatchMapping("/{id}/hide")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<ProfileResponse>> hideCompanyProfile(
            @PathVariable String id) {

        ProfileResponse response = profileService.hideCompanyProfile(id);
        return ResponseEntity.ok(ApiResponse.success(response, "Profile hidden successfully"));
    }

    @PatchMapping("/{id}/restore")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<ProfileResponse>> restoreCompanyProfile(
            @PathVariable String id) {

        ProfileResponse response = profileService.restoreCompanyProfile(id);
        return ResponseEntity.ok(ApiResponse.success(response, "Profile restored successfully"));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteCompanyProfile(
            @PathVariable String id) {

        profileService.deleteProfilePermanently(id);
        return ResponseEntity.ok(ApiResponse.success(null, "Profile deleted permanently"));
    }
}
