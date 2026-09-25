package com.apms.domain.news.controller;

import com.apms.common.response.ApiResponse;
import com.apms.domain.news.service.CompanyNewsBackfillService;
import com.apms.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/backfill")
@RequiredArgsConstructor
public class CompanyNewsBackfillController {

    private final CompanyNewsBackfillService backfillService;

    @PostMapping("/company-news")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<String>> backfillCompanyNews() {
        UserDetailsImpl currentUser = (UserDetailsImpl) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        String result = backfillService.backfillApprovedSubmissions(currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success(result));
    }
}
