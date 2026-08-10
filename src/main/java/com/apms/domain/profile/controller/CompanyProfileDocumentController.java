package com.apms.domain.profile.controller;

import com.apms.common.response.PageResponse;
import com.apms.domain.document.dto.CompanyDocumentResponse;
import com.apms.domain.document.service.CompanyDocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/company-profiles")
@RequiredArgsConstructor
public class CompanyProfileDocumentController {

    private final CompanyDocumentService companyDocumentService;

    @GetMapping("/{companyProfileId}/documents")
    @PreAuthorize("hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF', 'SYSTEM_ADMIN')")
    public ResponseEntity<PageResponse<CompanyDocumentResponse>> getPublishedDocuments(
            @PathVariable String companyProfileId,
            Pageable pageable) {

        Page<CompanyDocumentResponse> documents = companyDocumentService.getPublishedDocuments(companyProfileId, pageable);
        return ResponseEntity.ok(PageResponse.of(documents));
    }

    @PostMapping("/{companyProfileId}/documents/reconcile")
    @PreAuthorize("hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<java.util.Map<String, Object>> reconcilePublishedDocuments(
            @PathVariable String companyProfileId) {

        int reconciled = companyDocumentService.reconcilePublishedDocuments(companyProfileId);
        return ResponseEntity.ok(java.util.Map.of(
                "companyProfileId", companyProfileId,
                "reconciled", reconciled
        ));
    }

    @GetMapping("/{companyProfileId}/documents/{documentId}/download")
    @PreAuthorize("hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF', 'SYSTEM_ADMIN')")
    public ResponseEntity<Resource> downloadDocument(
            @PathVariable String companyProfileId,
            @PathVariable String documentId,
            @RequestParam(defaultValue = "true") boolean download) {
        
        CompanyDocumentService.DocumentDownload file = companyDocumentService.downloadDocument(companyProfileId, documentId);
        ContentDisposition disposition = (download ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(file.fileName())
                .build();
        
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.mimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(file.resource());
    }
}
