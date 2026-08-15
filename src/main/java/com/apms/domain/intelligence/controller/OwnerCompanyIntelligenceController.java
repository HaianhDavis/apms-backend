package com.apms.domain.intelligence.controller;

import com.apms.common.response.ApiResponse;
import com.apms.domain.intelligence.dto.OwnerCompanyIntelligenceResponse;
import com.apms.domain.intelligence.service.OwnerCompanyIntelligenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/owner/company-intelligence")
@RequiredArgsConstructor
public class OwnerCompanyIntelligenceController {
    private final OwnerCompanyIntelligenceService intelligenceService;

    @GetMapping("/{companyId}")
    @PreAuthorize("hasRole('BUSINESS_OWNER')")
    public ResponseEntity<ApiResponse<OwnerCompanyIntelligenceResponse>> get(@PathVariable String companyId) {
        return ResponseEntity.ok(ApiResponse.success(intelligenceService.get(companyId)));
    }
}
