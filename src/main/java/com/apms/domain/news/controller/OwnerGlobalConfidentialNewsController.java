package com.apms.domain.news.controller;

import com.apms.common.enums.AuditAction;
import com.apms.common.response.ApiResponse;
import com.apms.common.response.PageResponse;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.news.dto.CompanyIntelligenceArticleResponse;
import com.apms.domain.news.entity.CompanyIntelligenceArticle;
import com.apms.domain.news.repository.CompanyIntelligenceArticleRepository;
import com.apms.domain.security.service.StepUpAuthenticationService;
import com.apms.security.UserDetailsImpl;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/owner/confidential-news")
@RequiredArgsConstructor
public class OwnerGlobalConfidentialNewsController {

    private final CompanyIntelligenceArticleRepository articleRepository;
    private final StepUpAuthenticationService stepUpAuthenticationService;
    private final AuditLogService auditLogService;
    private final com.apms.domain.user.repository.sql.UserProfileRepository userProfileRepository;

    @GetMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER', 'BUSINESS_DEVELOPMENT_STAFF')")
    public ResponseEntity<ApiResponse<PageResponse<CompanyIntelligenceArticleResponse>>> listAllArticles(
            @RequestParam(required = false) String companyProfileId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request,
            HttpServletResponse response) {
        
        setNoCacheHeaders(response);
        validateAccess(request);

        Pageable pageable = PageRequest.of(page, size);
        Page<CompanyIntelligenceArticle> articlesPage;
        if (StringUtils.hasText(companyProfileId)) {
            articlesPage = articleRepository.findByCompanyProfileIdAndIsDeletedFalseAndApprovedAtIsNotNull(companyProfileId, pageable);
        } else {
            articlesPage = articleRepository.findByIsDeletedFalseAndApprovedAtIsNotNull(pageable);
        }
        
        java.util.List<Long> approverIds = articlesPage.getContent().stream()
                .map(CompanyIntelligenceArticle::getApprovedByAccountId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .collect(java.util.stream.Collectors.toList());
        
        java.util.Map<Long, String> approverMap = approverIds.isEmpty() ? java.util.Collections.emptyMap() :
                userProfileRepository.findAllByAccountIdIn(approverIds).stream()
                        .collect(java.util.stream.Collectors.toMap(
                                p -> p.getAccount().getId(), 
                                p -> ((p.getFirstName() != null ? p.getFirstName() : "") + " " + (p.getLastName() != null ? p.getLastName() : "")).trim(), 
                                (a, b) -> a));

        PageResponse<CompanyIntelligenceArticleResponse> pageResponse = new PageResponse<>(
                articlesPage.getContent().stream().map(article -> toResponse(article, approverMap)).toList(),
                articlesPage.getNumber(),
                articlesPage.getSize(),
                articlesPage.getTotalElements(),
                articlesPage.getTotalPages(),
                articlesPage.isLast()
        );
        return ResponseEntity.ok(ApiResponse.success(pageResponse));
    }

    private void validateAccess(HttpServletRequest request) {
        UserDetailsImpl currentUser = getCurrentUser();
        auditLogService.log(currentUser.getId(), AuditAction.CONFIDENTIAL_NEWS_ACCESS_REQUESTED, "Global", "all", "Global confidential news access requested");

        // Validate step-up token
        String stepUpToken = request.getHeader("X-Step-Up-Token");
        if (!StringUtils.hasText(stepUpToken)) {
            auditLogService.log(currentUser.getId(), AuditAction.CONFIDENTIAL_NEWS_ACCESS_DENIED, "Global", "all", "Missing step-up token");
            throw new AccessDeniedException("STEP_UP_TOKEN_REQUIRED");
        }

        boolean isValid = stepUpAuthenticationService.isOwnerSecureSessionActive(currentUser.getId(), stepUpToken);
        if (!isValid) {
            auditLogService.log(currentUser.getId(), AuditAction.CONFIDENTIAL_NEWS_ACCESS_DENIED, "Global", "all", "Invalid or expired step-up token");
            throw new AccessDeniedException("STEP_UP_TOKEN_EXPIRED");
        }
        
        auditLogService.log(currentUser.getId(), AuditAction.INTERNAL_NEWS_ACCESS_GRANTED, "Global", "all", "Global confidential news accessed");
    }

    private void setNoCacheHeaders(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store, no-cache, must-revalidate");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
    }

    private CompanyIntelligenceArticleResponse toResponse(CompanyIntelligenceArticle article, java.util.Map<Long, String> approverMap) {
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
                .approvedBy(article.getApprovedByAccountId() != null ? approverMap.getOrDefault(article.getApprovedByAccountId(), "Unknown Approver") : null)
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
}
