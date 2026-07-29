package com.apms.domain.ai.controller;

import com.apms.common.response.ApiResponse;
import com.apms.domain.ai.dto.AiExtractionResult;
import com.apms.domain.ai.service.AiExtractionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AiController {

    private final AiExtractionService aiExtractionService;

    // ─────────────────────────────────────────────
    // POST /api/v1/ai/extract/{importJobId} (Legacy)
    // ─────────────────────────────────────────────
    @PostMapping("/ai/extract/{importJobId}")
    @PreAuthorize("hasRole('BUSINESS_DEVELOPMENT_STAFF')")
    public ResponseEntity<ApiResponse<AiExtractionResult>> extractCompanyDataLegacy(
            @PathVariable Long importJobId) {

        AiExtractionResult result = aiExtractionService.extractCompanyData(importJobId);
        return ResponseEntity.ok(ApiResponse.success(result, "AI extraction completed"));
    }

    // ─────────────────────────────────────────────
    // POST /api/v1/import-jobs/{importJobId}/ai-extractions
    // ─────────────────────────────────────────────
    @PostMapping("/import-jobs/{importJobId}/ai-extractions")
    @PreAuthorize("hasRole('BUSINESS_DEVELOPMENT_STAFF')")
    public ResponseEntity<ApiResponse<AiExtractionResult>> extractCompanyData(
            @PathVariable Long importJobId) {

        AiExtractionResult result = aiExtractionService.extractCompanyData(importJobId);
        return ResponseEntity.ok(ApiResponse.success(result, "AI extraction completed and cached"));
    }

    // ─────────────────────────────────────────────
    // GET /api/v1/import-jobs/{importJobId}/ai-extractions/latest
    // ─────────────────────────────────────────────
    @GetMapping("/import-jobs/{importJobId}/ai-extractions/latest")
    @PreAuthorize("hasAnyRole('BUSINESS_DEVELOPMENT_STAFF', 'BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_OWNER')")
    public ResponseEntity<ApiResponse<com.apms.domain.ai.AiExtractionCache>> getLatestExtraction(
            @PathVariable Long importJobId) {

        com.apms.domain.ai.AiExtractionCache result = aiExtractionService.getLatestExtraction(importJobId);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    // ─────────────────────────────────────────────
    // PATCH /api/v1/ai-extractions/{extractionId}
    // ─────────────────────────────────────────────
    @PatchMapping("/ai-extractions/{extractionId}")
    @PreAuthorize("hasRole('BUSINESS_DEVELOPMENT_STAFF')")
    public ResponseEntity<ApiResponse<com.apms.domain.ai.AiExtractionCache>> updateExtraction(
            @PathVariable String extractionId,
            @org.springframework.web.bind.annotation.RequestBody com.apms.domain.ai.dto.ExtractedCompanyData request,
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.apms.security.UserDetailsImpl currentUser) {

        com.apms.domain.ai.AiExtractionCache result = aiExtractionService.updateExtraction(extractionId, request, currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success(result, "AI extraction updated successfully"));
    }

    // ─────────────────────────────────────────────
    // GET /api/v1/ai-extractions/{extractionId}/quality
    // ─────────────────────────────────────────────
    @GetMapping("/ai-extractions/{extractionId}/quality")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF')")
    public ResponseEntity<ApiResponse<com.apms.domain.ai.AiExtractionCache>> getExtractionQuality(
            @PathVariable String extractionId) {

        com.apms.domain.ai.AiExtractionCache result = aiExtractionService.getExtractionById(extractionId);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    // ─────────────────────────────────────────────
    // PATCH /api/v1/ai-extractions/{extractionId}/fields/{fieldName}/review
    // ─────────────────────────────────────────────
    @PatchMapping("/ai-extractions/{extractionId}/fields/{fieldName}/review")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF')")
    public ResponseEntity<ApiResponse<com.apms.domain.ai.AiExtractionCache>> reviewExtractionField(
            @PathVariable String extractionId,
            @PathVariable String fieldName,
            @org.springframework.web.bind.annotation.RequestBody @jakarta.validation.Valid com.apms.domain.ai.dto.ExtractionReviewRequest request,
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.apms.security.UserDetailsImpl currentUser) {

        com.apms.domain.ai.AiExtractionCache result = aiExtractionService.reviewField(extractionId, fieldName, request, currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success(result, "Extraction field reviewed successfully"));
    }

    // ─────────────────────────────────────────────
    // POST /api/v1/ai-extractions/{extractionId}/review/complete
    // ─────────────────────────────────────────────
    @PostMapping("/ai-extractions/{extractionId}/review/complete")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF')")
    public ResponseEntity<ApiResponse<com.apms.domain.ai.AiExtractionCache>> completeExtractionReview(
            @PathVariable String extractionId,
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.apms.security.UserDetailsImpl currentUser) {

        com.apms.domain.ai.AiExtractionCache result = aiExtractionService.completeReview(extractionId, currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success(result, "Extraction review completed successfully"));
    }
}
