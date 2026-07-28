package com.apms.domain.project.controller;

import com.apms.common.response.ApiResponse;
import com.apms.domain.profile.dto.ProfileResponse;
import com.apms.domain.profile.service.ProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/company-profiles")
@RequiredArgsConstructor
public class ProjectCompanyProfileController {

    private final ProfileService profileService;

    @GetMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or (hasAnyRole('BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF') and @projectSecurity.isMemberOrOwner(#projectId))")
    public ResponseEntity<ApiResponse<List<ProfileResponse>>> getApprovedProfiles(@PathVariable Long projectId) {
        return ResponseEntity.ok(ApiResponse.success(profileService.getApprovedProfilesForProject(projectId)));
    }
}
