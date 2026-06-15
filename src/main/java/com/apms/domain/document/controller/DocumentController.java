package com.apms.domain.document.controller;

import com.apms.common.response.ApiResponse;
import com.apms.common.response.PageResponse;
import com.apms.domain.document.dto.ImportJobResponse;
import com.apms.domain.document.dto.ManualInputRequest;
import com.apms.domain.document.service.DocumentService;
import com.apms.security.UserDetailsImpl;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    // ─────────────────────────────────────────────
    // POST /api/v1/projects/{projectId}/documents/upload
    // Role: RESEARCH_STAFF, BUSINESS_DEVELOPMENT_MANAGER
    // ─────────────────────────────────────────────
    @PostMapping("/projects/{projectId}/documents/upload")
    @PreAuthorize("hasAnyRole('RESEARCH_STAFF', 'BUSINESS_DEVELOPMENT_MANAGER') and @projectSecurity.isMember(#projectId)")
    public ResponseEntity<ApiResponse<ImportJobResponse>> uploadDocument(
            @PathVariable Long projectId,
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        ImportJobResponse response = documentService.uploadDocument(projectId, file, currentUser.getId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response, "Document uploaded successfully"));
    }

    // ─────────────────────────────────────────────
    // POST /api/v1/projects/{projectId}/documents/manual
    // Role: RESEARCH_STAFF, BUSINESS_DEVELOPMENT_MANAGER
    // ─────────────────────────────────────────────
    @PostMapping("/projects/{projectId}/documents/manual")
    @PreAuthorize("hasAnyRole('RESEARCH_STAFF', 'BUSINESS_DEVELOPMENT_MANAGER') and @projectSecurity.isMember(#projectId)")
    public ResponseEntity<ApiResponse<ImportJobResponse>> manualInput(
            @PathVariable Long projectId,
            @Valid @RequestBody ManualInputRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        ImportJobResponse response = documentService.manualInput(projectId, request, currentUser.getId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response, "Manual input created successfully"));
    }

    // ─────────────────────────────────────────────
    // GET /api/v1/projects/{projectId}/documents
    // Role: RESEARCH_STAFF, BUSINESS_DEVELOPMENT_MANAGER
    // ─────────────────────────────────────────────
    @GetMapping("/projects/{projectId}/documents")
    @PreAuthorize("hasAnyRole('RESEARCH_STAFF', 'BUSINESS_DEVELOPMENT_MANAGER') and @projectSecurity.isMemberOrOwner(#projectId)")
    public ResponseEntity<ApiResponse<PageResponse<ImportJobResponse>>> getProjectDocuments(
            @PathVariable Long projectId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        PageResponse<ImportJobResponse> response = PageResponse.of(
                documentService.getProjectImportJobs(projectId, pageable));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // ─────────────────────────────────────────────
    // GET /api/v1/import-jobs/{importJobId}
    // Role: RESEARCH_STAFF, BUSINESS_DEVELOPMENT_MANAGER
    // ─────────────────────────────────────────────
    @GetMapping("/import-jobs/{importJobId}")
    @PreAuthorize("hasAnyRole('RESEARCH_STAFF', 'BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<ApiResponse<ImportJobResponse>> getImportJob(
            @PathVariable Long importJobId) {

        return ResponseEntity.ok(ApiResponse.success(documentService.getImportJob(importJobId)));
    }
}
