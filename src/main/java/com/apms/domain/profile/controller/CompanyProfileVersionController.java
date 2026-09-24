package com.apms.domain.profile.controller;

import com.apms.common.response.ApiResponse;
import com.apms.common.response.PageResponse;
import com.apms.domain.profile.dto.CompanyProfileVersionResponse;
import com.apms.domain.profile.service.CompanyProfileVersionService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(path = {"/api/v1/company-profiles/{companyProfileId}/versions", "/api/v1/profiles/{companyProfileId}/versions"})
@RequiredArgsConstructor
public class CompanyProfileVersionController {

    private final CompanyProfileVersionService versionService;

    @GetMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or (hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF') and @companyScope.canAccessCompany(#companyProfileId))")
    public ResponseEntity<ApiResponse<PageResponse<CompanyProfileVersionResponse>>> getVersions(
            @PathVariable String companyProfileId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<CompanyProfileVersionResponse> pageResult = versionService.getVersions(companyProfileId, pageable);

        PageResponse<CompanyProfileVersionResponse> response = PageResponse.of(pageResult);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/{version}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or (hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF') and @companyScope.canAccessCompany(#companyProfileId))")
    public ResponseEntity<ApiResponse<CompanyProfileVersionResponse>> getVersion(
            @PathVariable String companyProfileId,
            @PathVariable String version) {
        return ResponseEntity.ok(ApiResponse.success(versionService.getVersion(companyProfileId, version)));
    }
}
