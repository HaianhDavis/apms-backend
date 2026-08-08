package com.apms.domain.profile.controller;

import com.apms.domain.document.dto.CompanyDocumentResponse;
import com.apms.domain.document.service.CompanyDocumentService;
import com.apms.domain.profile.service.ProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
    private final ProfileService profileService;

    @GetMapping("/{companyProfileId}/documents")
    @PreAuthorize("hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF', 'SYSTEM_ADMIN')")
    public ResponseEntity<Page<CompanyDocumentResponse>> getPublishedDocuments(
            @PathVariable String companyProfileId,
            Pageable pageable) {
        
        profileService.getProfileByCompanyId(companyProfileId);
        
        Page<CompanyDocumentResponse> documents = companyDocumentService.getPublishedDocuments(companyProfileId, pageable);
        return ResponseEntity.ok(documents);
    }

    @GetMapping("/{companyProfileId}/documents/{documentId}/download")
    @PreAuthorize("hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF', 'SYSTEM_ADMIN')")
    public ResponseEntity<Resource> downloadDocument(
            @PathVariable String companyProfileId,
            @PathVariable String documentId) {
        
        profileService.getProfileByCompanyId(companyProfileId);
        
        CompanyDocumentService.DocumentDownload download = companyDocumentService.downloadDocument(companyProfileId, documentId);
        
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.mimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + download.fileName() + "\"")
                .body(download.resource());
    }
}
