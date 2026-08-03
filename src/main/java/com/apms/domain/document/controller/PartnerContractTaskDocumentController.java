package com.apms.domain.document.controller;

import com.apms.common.response.ApiResponse;
import com.apms.domain.document.dto.ImportJobResponse;
import com.apms.domain.document.service.DocumentService;
import com.apms.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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
}
