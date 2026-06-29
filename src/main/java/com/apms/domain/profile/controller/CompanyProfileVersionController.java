package com.apms.domain.profile.controller;

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
@RequestMapping("/api/v1/company-profiles/{companyProfileId}/versions")
@RequiredArgsConstructor
public class CompanyProfileVersionController {

    private final CompanyProfileVersionService versionService;

    @GetMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF')")
    public ResponseEntity<PageResponse<CompanyProfileVersionResponse>> getVersions(
            @PathVariable String companyProfileId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<CompanyProfileVersionResponse> pageResult = versionService.getVersions(companyProfileId, pageable);
        
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

    @GetMapping("/{version}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF')")
    public ResponseEntity<CompanyProfileVersionResponse> getVersion(
            @PathVariable String companyProfileId,
            @PathVariable Integer version) {
        return ResponseEntity.ok(versionService.getVersion(companyProfileId, version));
    }
}
