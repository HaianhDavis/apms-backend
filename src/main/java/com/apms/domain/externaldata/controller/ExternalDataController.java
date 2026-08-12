package com.apms.domain.externaldata.controller;

import com.apms.common.enums.ExternalDataCategory;
import com.apms.common.response.ApiResponse;
import com.apms.common.response.PageResponse;
import com.apms.domain.externaldata.dto.ExternalDataItemResponse;
import com.apms.domain.externaldata.service.ExternalDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/v1/external-data")
@RequiredArgsConstructor
public class ExternalDataController {

    private final ExternalDataService externalDataService;

    @GetMapping("/news")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','BUSINESS_OWNER','BUSINESS_DEVELOPMENT_MANAGER','BUSINESS_DEVELOPMENT_STAFF')")
    public ResponseEntity<ApiResponse<PageResponse<ExternalDataItemResponse>>> getNews(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime toDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "publishedAt"));
        PageResponse<ExternalDataItemResponse> response = PageResponse.of(
                // A news feed contains every crawled article. RISK and OPPORTUNITY are
                // intelligence signals, not mutually exclusive article types.
                externalDataService.getExternalData(null, keyword, source, projectId, fromDate, toDate, pageable));

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/opportunities")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','BUSINESS_OWNER','BUSINESS_DEVELOPMENT_MANAGER','BUSINESS_DEVELOPMENT_STAFF')")
    public ResponseEntity<ApiResponse<PageResponse<ExternalDataItemResponse>>> getOpportunities(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime toDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "publishedAt"));
        PageResponse<ExternalDataItemResponse> response = PageResponse.of(
                externalDataService.getExternalData(ExternalDataCategory.OPPORTUNITY, keyword, source, projectId, fromDate, toDate, pageable));

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/risks")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','BUSINESS_OWNER','BUSINESS_DEVELOPMENT_MANAGER','BUSINESS_DEVELOPMENT_STAFF')")
    public ResponseEntity<ApiResponse<PageResponse<ExternalDataItemResponse>>> getRisks(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime toDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "publishedAt"));
        PageResponse<ExternalDataItemResponse> response = PageResponse.of(
                externalDataService.getExternalData(ExternalDataCategory.RISK, keyword, source, projectId, fromDate, toDate, pageable));

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/fetch")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','BUSINESS_OWNER','BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<ApiResponse<String>> fetch(@RequestParam(required = false) Long projectId) {
        String msg = externalDataService.fetch(projectId);
        return ResponseEntity.ok(ApiResponse.success(msg, msg));
    }

    @PostMapping("/fetch-approved-profiles")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','BUSINESS_OWNER','BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<ApiResponse<String>> fetchApprovedProfiles() {
        String msg = externalDataService.fetchAllApprovedProfiles();
        return ResponseEntity.ok(ApiResponse.success(msg, msg));
    }

    @PostMapping("/analyze")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','BUSINESS_OWNER','BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<ApiResponse<String>> analyze(@RequestParam(required = false) Long projectId) {
        String msg = externalDataService.analyze(projectId);
        return ResponseEntity.ok(ApiResponse.success(msg, msg));
    }
}
