package com.apms.domain.externaldata.controller;

import com.apms.common.enums.ExternalDataCategory;
import com.apms.common.response.ApiResponse;
import com.apms.common.response.PageResponse;
import com.apms.domain.externaldata.dto.ExternalDataItemResponse;
import com.apms.domain.externaldata.service.ExternalDataService;
import com.apms.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/external-data")
@RequiredArgsConstructor
public class ExternalDataController {

    private final ExternalDataService externalDataService;
    private final com.apms.common.security.StaffCompanyScopeEvaluator companyScope;

    @GetMapping("/news")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','BUSINESS_OWNER','BUSINESS_DEVELOPMENT_MANAGER','BUSINESS_DEVELOPMENT_STAFF')")
    public ResponseEntity<ApiResponse<PageResponse<ExternalDataItemResponse>>> getNews(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String companyName,
            @RequestParam(required = false) String sentiment,
            @RequestParam(required = false) String importance,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime toDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "publishedAt"));
        Set<String> allowedCompanyIds = companyScope.allowedCompanyIds();
        PageResponse<ExternalDataItemResponse> response = PageResponse.of(
                externalDataService.getExternalData(ExternalDataCategory.NEWS, keyword, source, companyName, fromDate, toDate, sentiment, importance, allowedCompanyIds, pageable));

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/news/{id}")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','BUSINESS_OWNER','BUSINESS_DEVELOPMENT_MANAGER','BUSINESS_DEVELOPMENT_STAFF')")
    public ResponseEntity<ApiResponse<ExternalDataItemResponse>> getNewsById(@PathVariable String id) {
        ExternalDataItemResponse item = externalDataService.getExternalDataById(id);
        if (item != null) {
            Set<String> allowedCompanyIds = companyScope.allowedCompanyIds();
            if (allowedCompanyIds != null) {
                String relatedCompanyId = item.getRelatedCompanyId();
                if (relatedCompanyId == null || !allowedCompanyIds.contains(relatedCompanyId)) {
                    return ResponseEntity.status(403).body(ApiResponse.error("Access denied"));
                }
            }
            return ResponseEntity.ok(ApiResponse.success(item));
        }
        return ResponseEntity.status(404).body(ApiResponse.error("Article not found"));
    }

    @GetMapping("/opportunities")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','BUSINESS_OWNER','BUSINESS_DEVELOPMENT_MANAGER','BUSINESS_DEVELOPMENT_STAFF')")
    public ResponseEntity<ApiResponse<PageResponse<ExternalDataItemResponse>>> getOpportunities(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String companyName,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime toDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "publishedAt"));
        Set<String> allowedCompanyIds = companyScope.allowedCompanyIds();
        PageResponse<ExternalDataItemResponse> response = PageResponse.of(
                externalDataService.getExternalData(ExternalDataCategory.OPPORTUNITY, keyword, source, companyName, fromDate, toDate, null, null, allowedCompanyIds, pageable));

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/risks")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','BUSINESS_OWNER','BUSINESS_DEVELOPMENT_MANAGER','BUSINESS_DEVELOPMENT_STAFF')")
    public ResponseEntity<ApiResponse<PageResponse<ExternalDataItemResponse>>> getRisks(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String companyName,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime toDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "publishedAt"));
        Set<String> allowedCompanyIds = companyScope.allowedCompanyIds();
        PageResponse<ExternalDataItemResponse> response = PageResponse.of(
                externalDataService.getExternalData(ExternalDataCategory.RISK, keyword, source, companyName, fromDate, toDate, null, null, allowedCompanyIds, pageable));

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/fetch")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<ApiResponse<String>> simulateFetch() {
        String msg = externalDataService.simulateFetch();
        return ResponseEntity.ok(ApiResponse.success(msg, msg));
    }

    @PostMapping("/analyze")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<ApiResponse<String>> simulateAnalyze() {
        String msg = externalDataService.simulateAnalyze();
        return ResponseEntity.ok(ApiResponse.success(msg, msg));
    }
}
