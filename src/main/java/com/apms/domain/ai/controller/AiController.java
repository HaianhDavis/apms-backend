package com.apms.domain.ai.controller;

import com.apms.common.response.ApiResponse;
import com.apms.domain.ai.dto.AiExtractionResult;
import com.apms.domain.ai.service.AiExtractionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiExtractionService aiExtractionService;

    // ─────────────────────────────────────────────
    // POST /api/v1/ai/extract/{importJobId}
    // Role: RESEARCH_STAFF
    // ─────────────────────────────────────────────
    @PostMapping("/extract/{importJobId}")
    @PreAuthorize("hasRole('RESEARCH_STAFF')")
    public ResponseEntity<ApiResponse<AiExtractionResult>> extractCompanyData(
            @PathVariable Long importJobId) {

        AiExtractionResult result = aiExtractionService.extractCompanyData(importJobId);
        return ResponseEntity.ok(ApiResponse.success(result, "AI extraction completed (skeleton)"));
    }
}
