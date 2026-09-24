package com.apms.domain.dashboard.controller;

import com.apms.common.response.ApiResponse;
import com.apms.domain.dashboard.dto.OwnerInsightDto;
import com.apms.domain.dashboard.service.OwnerInsightsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/v1/owner/insights")
@RequiredArgsConstructor
@Tag(name = "Owner Insights", description = "Business Owner Insights and Recommendations")
public class OwnerInsightsController {

    private final OwnerInsightsService ownerInsightsService;

    @GetMapping
    @PreAuthorize("hasRole('BUSINESS_OWNER')")
    @Operation(summary = "Get Owner insights", description = "Retrieves deterministic insights and recommendations for the Business Owner ecosystem")
    public ResponseEntity<ApiResponse<Page<OwnerInsightDto>>> getInsights(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String companyProfileId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime toDate,
            Pageable pageable) {

        Page<OwnerInsightDto> insights = ownerInsightsService.getInsights(type, companyProfileId, fromDate, toDate, pageable);
        return ResponseEntity.ok(ApiResponse.success(insights));
    }
}
