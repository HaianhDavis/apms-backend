package com.apms.domain.companymember.controller;

import com.apms.common.response.ApiResponse;
import com.apms.domain.companymember.dto.CompanyMemberResearchDraftRequest;
import com.apms.domain.companymember.dto.CompanyMemberResearchDraftResponse;
import com.apms.domain.companymember.service.CompanyMemberResearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/tasks/{taskId}/company-members")
@Tag(name = "Company Member Research", description = "Endpoints for managing company member research drafts")
@RequiredArgsConstructor
public class CompanyMemberResearchController {

    private final CompanyMemberResearchService researchService;

    @Operation(summary = "Get current company member research draft")
    @GetMapping("/draft")
    public ResponseEntity<ApiResponse<CompanyMemberResearchDraftResponse>> getDraft(
            @PathVariable Long projectId,
            @PathVariable Long taskId) {
        CompanyMemberResearchDraftResponse response = researchService.getDraft(projectId, taskId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "Save or update company member research draft")
    @PostMapping("/draft")
    public ResponseEntity<ApiResponse<CompanyMemberResearchDraftResponse>> saveDraft(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @Valid @RequestBody CompanyMemberResearchDraftRequest request) {
        CompanyMemberResearchDraftResponse response = researchService.saveDraft(projectId, taskId, request);
        return ResponseEntity.ok(ApiResponse.success(response, "Draft saved successfully"));
    }

    @Operation(summary = "Submit company member research draft for review")
    @PostMapping("/submit")
    public ResponseEntity<ApiResponse<Void>> submitDraft(
            @PathVariable Long projectId,
            @PathVariable Long taskId) {
        researchService.submitDraft(projectId, taskId);
        return ResponseEntity.ok(ApiResponse.success(null, "Draft submitted for review successfully"));
    }
}
