package com.apms.domain.profile.controller;

import com.apms.common.enums.AuditAction;
import com.apms.common.response.PageResponse;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.document.dto.CompanyDocumentResponse;
import com.apms.domain.document.service.CompanyDocumentService;
import com.apms.domain.profile.service.CompanyProfileAccessService;
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
    private final com.apms.common.security.StaffCompanyScopeEvaluator companyScope;
    private final AuditLogService auditLogService;

    @GetMapping("/{companyProfileId}/documents")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER') or (hasRole('BUSINESS_DEVELOPMENT_STAFF') and #projectId != null and @companyScope.canReadCompanyProfileFromProject(principal.id, #projectId, #companyProfileId))")
    public ResponseEntity<PageResponse<CompanyDocumentResponse>> getPublishedDocuments(
            @PathVariable String companyProfileId,
            @RequestParam(required = false) Long projectId,
            Pageable pageable,
            HttpServletRequest request,
            HttpServletResponse response,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        setNoCacheHeaders(response);
        validateSecureDocumentAccess(companyProfileId, projectId, request, currentUser, null);
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
        validateSecureDocumentAccess(companyProfileId, null, request, currentUser, null);
        int reconciled = companyDocumentService.reconcilePublishedDocuments(companyProfileId);
        return ResponseEntity.ok(java.util.Map.of(
                "companyProfileId", companyProfileId,
                "reconciled", reconciled
        ));
    }

    @GetMapping("/{companyProfileId}/documents/{documentId}/download")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER') or (hasRole('BUSINESS_DEVELOPMENT_STAFF') and #projectId != null and @companyScope.canReadCompanyProfileFromProject(principal.id, #projectId, #companyProfileId))")
    public ResponseEntity<Resource> downloadDocument(
            @PathVariable String companyProfileId,
            @PathVariable String documentId,
            @RequestParam(required = false) Long projectId,
            @RequestParam(defaultValue = "true") boolean download,
            HttpServletRequest request,
            HttpServletResponse response,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {
        
        setNoCacheHeaders(response);
        validateSecureDocumentAccess(companyProfileId, projectId, request, currentUser, documentId);
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
            Long projectId,
            HttpServletRequest request,
            UserDetailsImpl currentUser,
            String documentId) {

        if (currentUser != null && currentUser.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_BUSINESS_DEVELOPMENT_STAFF"))) {
            if (projectId == null || !companyScope.canReadCompanyProfileFromProject(currentUser.getId(), projectId, companyProfileId)) {
                throw new AccessDeniedException("STAFF_NOT_AUTHORIZED_FOR_COMPANY_PROFILE");
            }
        } else {
            companyProfileAccessService.requireOwnerAccessibleOfficialCompanyProfile(companyProfileId, currentUser);
        }
    }

    private void setNoCacheHeaders(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store, no-cache, must-revalidate");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
    }
}
