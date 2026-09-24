package com.apms.domain.document.controller;

import com.apms.common.response.ApiResponse;
import com.apms.common.response.PageResponse;
import com.apms.domain.document.dto.ImportJobResponse;
import com.apms.domain.document.service.DocumentService;
import com.apms.security.UserDetailsImpl;
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
@RequestMapping("/api/v1/projects/{projectId}/tasks/{taskId}/partner-contracts")
@RequiredArgsConstructor
public class PartnerContractTaskDocumentController {

    private final DocumentService documentService;

    // ─────────────────────────────────────────────
    // POST /api/v1/projects/{projectId}/tasks/{taskId}/partner-contracts/documents
    // Role: BUSINESS_DEVELOPMENT_STAFF, BUSINESS_DEVELOPMENT_MANAGER
    // ─────────────────────────────────────────────
    @PostMapping("/documents")
    @PreAuthorize("hasAnyRole('BUSINESS_DEVELOPMENT_STAFF', 'BUSINESS_DEVELOPMENT_MANAGER') and @projectSecurity.isMember(#projectId)")
    public ResponseEntity<ApiResponse<ImportJobResponse>> uploadPartnerContractDocument(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        ImportJobResponse response = documentService.uploadPartnerContractDocument(projectId, taskId, file, currentUser.getId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response, "Partner contract document uploaded successfully"));
    }

    @GetMapping("/documents")
    @PreAuthorize("hasAnyRole('BUSINESS_DEVELOPMENT_STAFF', 'BUSINESS_DEVELOPMENT_MANAGER') and @projectSecurity.isMember(#projectId)")
    public ResponseEntity<ApiResponse<PageResponse<ImportJobResponse>>> listPartnerContractDocuments(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @RequestParam(defaultValue = "false") boolean includeHidden,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        PageRequest pageable = PageRequest.of(page, Math.min(size, 100), Sort.by(Sort.Direction.DESC, "createdAt"));
        PageResponse<ImportJobResponse> response = PageResponse.of(
                documentService.getPartnerContractTaskDocuments(projectId, taskId, includeHidden, pageable));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/documents/{rawDocumentId}/download")
    @PreAuthorize("hasAnyRole('BUSINESS_DEVELOPMENT_STAFF', 'BUSINESS_DEVELOPMENT_MANAGER') and @projectSecurity.isMember(#projectId)")
    public ResponseEntity<Resource> downloadPartnerContractDocument(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @PathVariable String rawDocumentId,
            @RequestParam(defaultValue = "false") boolean download) {

        DocumentService.DocumentDownload file = documentService.getPartnerContractTaskDocumentDownload(projectId, taskId, rawDocumentId);
        ContentDisposition disposition = (download ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(file.fileName())
                .build();

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.mimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(file.resource());
    }

    @DeleteMapping("/documents/{rawDocumentId}")
    @PreAuthorize("hasAnyRole('BUSINESS_DEVELOPMENT_STAFF', 'BUSINESS_DEVELOPMENT_MANAGER') and @projectSecurity.isMember(#projectId)")
    public ResponseEntity<ApiResponse<Void>> deletePartnerContractDocument(
            @PathVariable Long projectId,
            @PathVariable Long taskId,
            @PathVariable String rawDocumentId,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        documentService.deletePartnerContractTaskDocument(projectId, taskId, rawDocumentId, currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success(null, "Contract deleted successfully"));
    }
}
