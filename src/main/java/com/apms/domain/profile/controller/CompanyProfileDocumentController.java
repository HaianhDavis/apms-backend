package com.apms.domain.profile.controller;

import com.apms.common.enums.AuditAction;
import com.apms.common.response.PageResponse;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.document.dto.CompanyDocumentResponse;
import com.apms.domain.document.service.CompanyDocumentService;
import com.apms.domain.profile.service.CompanyProfileAccessService;
import com.apms.domain.security.service.StepUpAuthenticationService;
import com.apms.security.UserDetailsImpl;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/company-profiles")
@RequiredArgsConstructor
public class CompanyProfileDocumentController {

    private final CompanyDocumentService companyDocumentService;
    private final CompanyProfileAccessService companyProfileAccessService;
    private final StepUpAuthenticationService stepUpAuthenticationService;
    private final AuditLogService auditLogService;

    @GetMapping("/{companyProfileId}/documents")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<PageResponse<CompanyDocumentResponse>> getPublishedDocuments(
            @PathVariable String companyProfileId,
            Pageable pageable,
            HttpServletRequest request,
            HttpServletResponse response,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        setNoCacheHeaders(response);
        validateSecureDocumentAccess(companyProfileId, request, currentUser, null);
        Page<CompanyDocumentResponse> documents = companyDocumentService.getPublishedDocuments(companyProfileId, pageable);
        auditLogService.log(currentUser.getId(), AuditAction.COMPANY_DOCUMENT_LIST_VIEWED, "CompanyProfile", companyProfileId, "Company profile document list viewed");
        return ResponseEntity.ok(PageResponse.of(documents));
    }

    @PostMapping("/{companyProfileId}/documents/reconcile")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<java.util.Map<String, Object>> reconcilePublishedDocuments(
            @PathVariable String companyProfileId,
            HttpServletRequest request,
            HttpServletResponse response,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        setNoCacheHeaders(response);
        validateSecureDocumentAccess(companyProfileId, request, currentUser, null);
        int reconciled = companyDocumentService.reconcilePublishedDocuments(companyProfileId);
        return ResponseEntity.ok(java.util.Map.of(
                "companyProfileId", companyProfileId,
                "reconciled", reconciled
        ));
    }

    @GetMapping("/{companyProfileId}/documents/{documentId}/download")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER')")
    public ResponseEntity<Resource> downloadDocument(
            @PathVariable String companyProfileId,
            @PathVariable String documentId,
            @RequestParam(defaultValue = "true") boolean download,
            HttpServletRequest request,
            HttpServletResponse response,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        
        setNoCacheHeaders(response);
        validateSecureDocumentAccess(companyProfileId, request, currentUser, documentId);
        CompanyDocumentService.DocumentDownload file = companyDocumentService.downloadDocument(companyProfileId, documentId);
        ContentDisposition disposition = (download ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(file.fileName())
                .build();
        auditLogService.log(
                currentUser.getId(),
                download ? AuditAction.COMPANY_DOCUMENT_DOWNLOADED : AuditAction.COMPANY_DOCUMENT_PREVIEWED,
                "CompanyDocument",
                documentId,
                (download ? "Company profile document downloaded" : "Company profile document previewed")
                        + " for profile " + companyProfileId);
        
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.mimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(file.resource());
    }

    private void validateSecureDocumentAccess(
            String companyProfileId,
            HttpServletRequest request,
            UserDetailsImpl currentUser,
            String documentId) {

        companyProfileAccessService.requireOwnerAccessibleOfficialCompanyProfile(companyProfileId, currentUser);

        String stepUpToken = request.getHeader("X-Step-Up-Token");
        if (!StringUtils.hasText(stepUpToken)) {
            auditLogService.log(currentUser.getId(), AuditAction.COMPANY_DOCUMENT_ACCESS_DENIED, "CompanyProfile", companyProfileId, "Missing documents step-up token");
            throw new AccessDeniedException("TOTP_STEP_UP_REQUIRED");
        }

        boolean valid = stepUpAuthenticationService.isOwnerSecureSessionActive(currentUser.getId(), stepUpToken);
        if (!valid) {
            auditLogService.log(currentUser.getId(), AuditAction.COMPANY_DOCUMENT_ACCESS_DENIED,
                    documentId == null ? "CompanyProfile" : "CompanyDocument",
                    documentId == null ? companyProfileId : documentId,
                    "Invalid owner secure session for profile " + companyProfileId);
            throw new AccessDeniedException("TOTP_STEP_UP_REQUIRED");
        }
    }

    private void setNoCacheHeaders(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store, no-cache, must-revalidate");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
    }
}
