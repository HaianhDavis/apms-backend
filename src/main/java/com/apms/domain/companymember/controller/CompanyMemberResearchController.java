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

import com.apms.domain.companymember.dto.MemberImageUploadResponse;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;
import java.nio.file.Files;

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

    @Operation(summary = "Upload image for company member")
    @PostMapping(value = "/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<MemberImageUploadResponse>> uploadImage(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @RequestParam("file") MultipartFile file) {
        MemberImageUploadResponse response = researchService.uploadImage(projectId, taskId, file);
        return ResponseEntity.ok(ApiResponse.success(response, "Image uploaded successfully"));
    }

    @Operation(summary = "Get uploaded company member image")
    @GetMapping("/images/{filename}")
    public ResponseEntity<Resource> getImage(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @PathVariable String filename) {
        try {
            Resource resource = researchService.getImage(filename);
            String contentType = null;
            try {
                if (resource.getFile() != null) {
                    contentType = Files.probeContentType(resource.getFile().toPath());
                }
            } catch (Exception ignored) {
            }
            if (contentType == null) {
                contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE;
            }
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + resource.getFilename() + "\"")
                    .body(resource);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
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
