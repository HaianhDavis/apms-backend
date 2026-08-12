package com.apms.domain.news.controller;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.SystemRole;
import com.apms.common.exception.ResourceNotFoundException;
import com.apms.common.response.ApiResponse;
import com.apms.common.response.PageResponse;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.document.service.StorageService;
import com.apms.domain.news.dto.CompanyIntelligenceArticleResponse;
import com.apms.domain.news.entity.CompanyIntelligenceArticle;
import com.apms.domain.news.repository.CompanyIntelligenceArticleRepository;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.service.CompanyProfileAccessService;
import com.apms.domain.security.service.StepUpAuthenticationService;
import com.apms.security.UserDetailsImpl;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@RestController
@RequestMapping("/api/v1/company-profiles/{companyProfileId}/confidential-news")
@RequiredArgsConstructor
public class ConfidentialNewsController {

    private final CompanyIntelligenceArticleRepository articleRepository;
    private final CompanyProfileAccessService companyProfileAccessService;
    private final StepUpAuthenticationService stepUpAuthenticationService;
    private final StorageService storageService;
    private final AuditLogService auditLogService;

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<CompanyIntelligenceArticleResponse>>> listArticles(
            @PathVariable String companyProfileId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request,
            HttpServletResponse response) {
        
        setNoCacheHeaders(response);
        validateAccess(companyProfileId, request);

        int safeSize = Math.min(size, 100);
        Pageable pageable = PageRequest.of(page, safeSize);

        Page<CompanyIntelligenceArticle> articlesPage = articleRepository.findByCompanyProfileIdAndIsDeletedFalseAndApprovedAtIsNotNull(companyProfileId, pageable);
        
        PageResponse<CompanyIntelligenceArticleResponse> pageResponse = PageResponse.of(articlesPage.map(this::toResponse));
        
        return ResponseEntity.ok(ApiResponse.success(pageResponse));
    }

    @GetMapping("/{articleId}")
    public ResponseEntity<ApiResponse<CompanyIntelligenceArticleResponse>> getArticle(
            @PathVariable String companyProfileId,
            @PathVariable String articleId,
            HttpServletRequest request,
            HttpServletResponse response) {
        
        setNoCacheHeaders(response);
        validateAccess(companyProfileId, request);

        CompanyIntelligenceArticle article = articleRepository.findByIdAndCompanyProfileIdAndIsDeletedFalseAndApprovedAtIsNotNull(articleId, companyProfileId)
                .orElseThrow(() -> new ResourceNotFoundException("Article not found"));

        UserDetailsImpl currentUser = getCurrentUser();
        auditLogService.log(currentUser.getId(), AuditAction.CONFIDENTIAL_NEWS_ARTICLE_VIEWED, "CompanyIntelligenceArticle", article.getId(), "Article viewed");

        return ResponseEntity.ok(ApiResponse.success(toResponse(article)));
    }

    @GetMapping("/{articleId}/image")
    public ResponseEntity<Resource> getArticleImage(
            @PathVariable String companyProfileId,
            @PathVariable String articleId,
            HttpServletRequest request,
            HttpServletResponse response) {
        
        setNoCacheHeaders(response);
        validateAccess(companyProfileId, request);

        CompanyIntelligenceArticle article = articleRepository.findByIdAndCompanyProfileIdAndIsDeletedFalseAndApprovedAtIsNotNull(articleId, companyProfileId)
                .orElseThrow(() -> new ResourceNotFoundException("Article not found"));

        if (!StringUtils.hasText(article.getImageStorageKey())) {
            throw new ResourceNotFoundException("Article does not have an image");
        }

        try {
            Path filePath = storageService.load(article.getImageStorageKey());
            Resource resource = storageService.loadAsResource(article.getImageStorageKey());
            
            String contentType = Files.probeContentType(filePath);
            if (contentType == null) {
                contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE;
            }

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + resource.getFilename() + "\"")
                    .body(resource);
        } catch (IOException e) {
            log.error("Failed to read image file", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    private void validateAccess(String companyProfileId, HttpServletRequest request) {
        UserDetailsImpl currentUser = getCurrentUser();
        auditLogService.log(currentUser.getId(), AuditAction.CONFIDENTIAL_NEWS_ACCESS_REQUESTED, "CompanyProfile", companyProfileId, "Confidential news access requested");

        CompanyProfile profile = companyProfileAccessService.requireOwnerAccessibleOfficialCompanyProfile(companyProfileId, currentUser);

        // Validate step-up token
        String stepUpToken = request.getHeader("X-Step-Up-Token");
        if (!StringUtils.hasText(stepUpToken)) {
            auditLogService.log(currentUser.getId(), AuditAction.CONFIDENTIAL_NEWS_ACCESS_DENIED, "CompanyProfile", companyProfileId, "Missing step-up token");
            throw new AccessDeniedException("STEP_UP_TOKEN_REQUIRED");
        }

        boolean isValid = stepUpAuthenticationService.isOwnerSecureSessionActive(currentUser.getId(), stepUpToken);
        if (!isValid) {
            auditLogService.log(currentUser.getId(), AuditAction.CONFIDENTIAL_NEWS_ACCESS_DENIED, "CompanyProfile", companyProfileId, "Invalid owner secure session");
            throw new AccessDeniedException("TOTP_STEP_UP_REQUIRED");
        }
    }

    private void setNoCacheHeaders(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store, no-cache, must-revalidate");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
    }

    private CompanyIntelligenceArticleResponse toResponse(CompanyIntelligenceArticle article) {
        return CompanyIntelligenceArticleResponse.builder()
                .id(article.getId())
                .companyProfileId(article.getCompanyProfileId())
                .title(article.getTitle())
                .summary(article.getSummary())
                .content(article.getContent())
                .hasImage(StringUtils.hasText(article.getImageStorageKey()))
                .externalImageUrl(article.getExternalImageUrl())
                .sourceName(article.getSourceName())
                .sourceUrl(article.getSourceUrl())
                .author(article.getAuthor())
                .publishedAt(article.getPublishedAt())
                .capturedAt(article.getCapturedAt())
                .tags(article.getTags())
                .approvedAt(article.getApprovedAt())
                .createdAt(article.getCreatedAt())
                .updatedAt(article.getUpdatedAt())
                .build();
    }

    private UserDetailsImpl getCurrentUser() {
        if (SecurityContextHolder.getContext().getAuthentication() == null ||
                !(SecurityContextHolder.getContext().getAuthentication().getPrincipal() instanceof UserDetailsImpl)) {
            throw new AccessDeniedException("Unauthorized");
        }
        return (UserDetailsImpl) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    private boolean hasRole(UserDetailsImpl user, SystemRole role) {
        return user.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_" + role.name()));
    }
}
