package com.apms.domain.news.controller;

import com.apms.common.response.ApiResponse;
import com.apms.domain.news.dto.*;
import com.apms.domain.news.service.CompanyNewsResearchDraftService;
import com.apms.domain.news.service.CompanyNewsResearchSubmissionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/tasks/{taskId}/company-news")
@RequiredArgsConstructor
public class CompanyNewsResearchController {

    private final CompanyNewsResearchDraftService draftService;
    private final CompanyNewsResearchSubmissionService submissionService;

    @PostMapping("/drafts")
    public ResponseEntity<ApiResponse<CompanyNewsResearchDraftResponse>> createDraft(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @Valid @RequestBody CreateNewsResearchDraftRequest request) {
        CompanyNewsResearchDraftResponse response = draftService.createDraft(projectId, taskId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @GetMapping("/drafts")
    public ResponseEntity<ApiResponse<List<CompanyNewsResearchDraftResponse>>> getDrafts(
            @PathVariable Long projectId,
            @PathVariable Long taskId) {
        List<CompanyNewsResearchDraftResponse> response = draftService.getDrafts(projectId, taskId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/drafts/{draftId}")
    public ResponseEntity<ApiResponse<CompanyNewsResearchDraftResponse>> getDraft(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @PathVariable String draftId) {
        CompanyNewsResearchDraftResponse response = draftService.getDraft(projectId, taskId, draftId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PatchMapping("/drafts/{draftId}")
    public ResponseEntity<ApiResponse<CompanyNewsResearchDraftResponse>> updateDraft(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @PathVariable String draftId,
            @Valid @RequestBody UpdateNewsResearchDraftRequest request) {
        CompanyNewsResearchDraftResponse response = draftService.updateDraft(projectId, taskId, draftId, request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @DeleteMapping("/drafts/{draftId}")
    public ResponseEntity<ApiResponse<Void>> deleteDraft(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @PathVariable String draftId) {
        draftService.deleteDraft(projectId, taskId, draftId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PostMapping("/images")
    public ResponseEntity<ApiResponse<NewsImageUploadResponse>> uploadImage(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @RequestParam("file") MultipartFile file) {
        NewsImageUploadResponse response = draftService.uploadImage(projectId, taskId, file);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/submit")
    public ResponseEntity<ApiResponse<Void>> submitResearch(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @Valid @RequestBody SubmitCompanyNewsResearchRequest request) {
        submissionService.submitNewsResearch(projectId, taskId, request);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
