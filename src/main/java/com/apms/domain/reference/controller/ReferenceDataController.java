package com.apms.domain.reference.controller;

import com.apms.common.response.ApiResponse;
import com.apms.domain.reference.dto.IndustryCatalogResponse;
import com.apms.domain.reference.service.IndustryCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/reference-data")
@RequiredArgsConstructor
public class ReferenceDataController {

    private final IndustryCatalogService industryCatalogService;

    @GetMapping("/industries")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF')")
    public ResponseEntity<ApiResponse<List<IndustryCatalogResponse>>> getIndustries(
            @RequestParam(required = false) String search) {
        List<IndustryCatalogResponse> list = industryCatalogService.getActiveIndustries(search);
        return ResponseEntity.ok(ApiResponse.success(list));
    }
}
