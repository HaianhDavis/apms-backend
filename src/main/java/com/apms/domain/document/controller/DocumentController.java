package com.apms.domain.document.controller;

import com.apms.common.response.ApiResponse;
import com.apms.common.response.PageResponse;
import com.apms.domain.document.dto.ImportJobResponse;
import com.apms.domain.document.dto.ManualInputRequest;
import com.apms.domain.document.service.DocumentService;
import com.apms.security.UserDetailsImpl;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
    // Role: BUSINESS_DEVELOPMENT_STAFF, BUSINESS_DEVELOPMENT_MANAGER
    // ─────────────────────────────────────────────
    @PostMapping("/projects/{projectId}/documents/upload")
    @PreAuthorize("hasAnyRole('BUSINESS_DEVELOPMENT_STAFF', 'BUSINESS_DEVELOPMENT_MANAGER') and @projectSecurity.isMember(#projectId)")
    public ResponseEntity<ApiResponse<ImportJobResponse>> uploadDocument(
            @PathVariable Long projectId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "taskId", required = false) Long taskId,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        ImportJobResponse response = documentService.uploadDocument(projectId, taskId, file, currentUser.getId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response, "Document uploaded successfully"));
    }

    // ─────────────────────────────────────────────
    // POST /api/v1/projects/{projectId}/documents/manual
    // Role: BUSINESS_DEVELOPMENT_STAFF, BUSINESS_DEVELOPMENT_MANAGER
    // ─────────────────────────────────────────────
    @PostMapping("/projects/{projectId}/documents/manual")
    @PreAuthorize("hasAnyRole('BUSINESS_DEVELOPMENT_STAFF', 'BUSINESS_DEVELOPMENT_MANAGER') and @projectSecurity.isMember(#projectId)")
    public ResponseEntity<ApiResponse<ImportJobResponse>> manualInput(
            @PathVariable Long projectId,
            @RequestParam(required = false) Long taskId,
            @Valid @RequestBody ManualInputRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        ImportJobResponse response = documentService.manualInput(projectId, taskId, request, currentUser.getId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response, "Manual input created successfully"));
    }

    // ─────────────────────────────────────────────
    // GET /api/v1/projects/{projectId}/documents
    // Role: BUSINESS_DEVELOPMENT_STAFF, BUSINESS_DEVELOPMENT_MANAGER
    // ─────────────────────────────────────────────
    @GetMapping("/projects/{projectId}/documents")
    @PreAuthorize("hasAnyRole('BUSINESS_DEVELOPMENT_STAFF', 'BUSINESS_DEVELOPMENT_MANAGER') and @projectSecurity.isMemberOrOwner(#projectId)")
    public ResponseEntity<ApiResponse<PageResponse<ImportJobResponse>>> getProjectDocuments(
            @PathVariable Long projectId,
            @RequestParam(defaultValue = "false") boolean includeHidden,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        PageResponse<ImportJobResponse> response = PageResponse.of(
                documentService.getProjectImportJobs(projectId, includeHidden, pageable));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // ─────────────────────────────────────────────
    // GET /api/v1/import-jobs/{importJobId}
    // Role: BUSINESS_DEVELOPMENT_STAFF, BUSINESS_DEVELOPMENT_MANAGER
    // ─────────────────────────────────────────────
    @GetMapping("/import-jobs/{importJobId}")
    @PreAuthorize("hasAnyRole('BUSINESS_DEVELOPMENT_STAFF', 'BUSINESS_DEVELOPMENT_MANAGER') and @companyScope.canAccessImportJob(#importJobId)")
    public ResponseEntity<ApiResponse<ImportJobResponse>> getImportJob(
            @PathVariable Long importJobId) {

        return ResponseEntity.ok(ApiResponse.success(documentService.getImportJob(importJobId)));
    }

    @GetMapping("/projects/{projectId}/documents/{rawDocumentId}/download")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('BUSINESS_OWNER') or (hasAnyRole('BUSINESS_DEVELOPMENT_STAFF', 'BUSINESS_DEVELOPMENT_MANAGER'))")
    public ResponseEntity<Resource> downloadDocument(
            @PathVariable Long projectId,
            @PathVariable String rawDocumentId,
            @RequestParam(defaultValue = "false") boolean download) {

        DocumentService.DocumentDownload file = documentService.getDocumentDownload(projectId, rawDocumentId);
        ContentDisposition disposition = (download ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(file.fileName())
                .build();

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.mimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(file.resource());
    }

    @GetMapping("/documents/{rawDocumentId}/download")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('BUSINESS_OWNER') or hasAnyRole('BUSINESS_DEVELOPMENT_STAFF', 'BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<Resource> downloadRawDocument(
            @PathVariable String rawDocumentId,
            @RequestParam(defaultValue = "false") boolean download) {

        DocumentService.DocumentDownload file = documentService.getRawDocumentDownload(rawDocumentId);
        ContentDisposition disposition = (download ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(file.fileName())
                .build();

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.mimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(file.resource());
    }

    // ─────────────────────────────────────────────
    // PATCH /api/v1/projects/{projectId}/documents/{rawDocumentId}/visibility
    // Role: SYSTEM_ADMIN or (BUSINESS_DEVELOPMENT_MANAGER + isMemberOrOwner)
    // ─────────────────────────────────────────────
    @PatchMapping("/projects/{projectId}/documents/{rawDocumentId}/visibility")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or (hasRole('BUSINESS_DEVELOPMENT_MANAGER') and @projectSecurity.isMemberOrOwner(#projectId))")
    public ResponseEntity<ApiResponse<Void>> updateDocumentVisibility(
            @PathVariable Long projectId,
            @PathVariable String rawDocumentId,
            @Valid @RequestBody com.apms.domain.document.dto.DocumentVisibilityRequest request,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        documentService.updateDocumentVisibility(rawDocumentId, request.getHidden(), currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success(null, "Document visibility updated"));
    }

    // ─────────────────────────────────────────────
    // GET /api/v1/projects/{projectId}/tasks/{taskId}/documents
    // Role: SYSTEM_ADMIN, BUSINESS_OWNER, BUSINESS_DEVELOPMENT_MANAGER, BUSINESS_DEVELOPMENT_STAFF (if member or owner)
    // ─────────────────────────────────────────────
    @GetMapping("/projects/{projectId}/tasks/{taskId}/documents")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or (hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF') and @projectSecurity.isMemberOrOwner(#projectId))")
    public ResponseEntity<ApiResponse<PageResponse<ImportJobResponse>>> getTaskDocuments(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @RequestParam(defaultValue = "false") boolean includeHidden,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        PageResponse<ImportJobResponse> response = PageResponse.of(
                documentService.getTaskImportJobs(projectId, taskId, includeHidden, pageable));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/projects/{projectId}/tasks/{taskId}/documents/{rawDocumentId}/download")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('BUSINESS_OWNER') or (hasAnyRole('BUSINESS_DEVELOPMENT_STAFF', 'BUSINESS_DEVELOPMENT_MANAGER') and @projectSecurity.isMemberOrOwner(#projectId))")
    public ResponseEntity<Resource> downloadTaskDocument(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @PathVariable String rawDocumentId,
            @RequestParam(defaultValue = "false") boolean download) {

        DocumentService.DocumentDownload file = documentService.getTaskDocumentDownload(projectId, taskId, rawDocumentId);
        ContentDisposition disposition = (download ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(file.fileName())
                .build();

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.mimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(file.resource());
    }

    // ─────────────────────────────────────────────
    // DELETE /api/v1/projects/{projectId}/tasks/{taskId}/documents/{rawDocumentId}
    // Role: SYSTEM_ADMIN, BUSINESS_DEVELOPMENT_MANAGER, BUSINESS_DEVELOPMENT_STAFF (if project member)
    // ─────────────────────────────────────────────
    @DeleteMapping("/projects/{projectId}/tasks/{taskId}/documents/{rawDocumentId}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or (hasRole('BUSINESS_DEVELOPMENT_MANAGER') and @projectSecurity.isMemberOrOwner(#projectId)) or (hasRole('BUSINESS_DEVELOPMENT_STAFF') and @projectSecurity.isMember(#projectId))")
    public ResponseEntity<ApiResponse<Void>> deleteTaskDocument(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @PathVariable String rawDocumentId,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        documentService.deleteDocument(projectId, taskId, rawDocumentId, currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success(null, "Document deleted"));
    }

    // ─────────────────────────────────────────────
    // DELETE /api/v1/projects/{projectId}/documents/{rawDocumentId}
    // Role: SYSTEM_ADMIN, BUSINESS_DEVELOPMENT_MANAGER, BUSINESS_DEVELOPMENT_STAFF (if project member)
    // ─────────────────────────────────────────────
    @DeleteMapping("/projects/{projectId}/documents/{rawDocumentId}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or (hasRole('BUSINESS_DEVELOPMENT_MANAGER') and @projectSecurity.isMemberOrOwner(#projectId)) or (hasRole('BUSINESS_DEVELOPMENT_STAFF') and @projectSecurity.isMember(#projectId))")
    public ResponseEntity<ApiResponse<Void>> deleteDocument(
            @PathVariable Long projectId,
            @PathVariable String rawDocumentId,
            @RequestParam(required = false) Long taskId,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        documentService.deleteDocument(projectId, taskId, rawDocumentId, currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success(null, "Document deleted"));
    }
}
