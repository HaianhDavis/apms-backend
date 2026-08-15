package com.apms.domain.crawler.controller;

import com.apms.common.response.ApiResponse;
import com.apms.common.response.PageResponse;
import com.apms.domain.crawler.domain.CrawledArticle;
import com.apms.domain.crawler.service.CompanyCrawlerService;
import com.apms.domain.externaldata.dto.ExternalDataItemResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CompanyCrawlerController {

    private final CompanyCrawlerService companyCrawlerService;

    @GetMapping("/companies/{companyId}/articles")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','BUSINESS_OWNER','BUSINESS_DEVELOPMENT_MANAGER','BUSINESS_DEVELOPMENT_STAFF') and @companyScope.canAccessCompany(#companyId)")
    public ResponseEntity<ApiResponse<PageResponse<ExternalDataItemResponse>>> getCompanyArticles(
            @PathVariable String companyId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "publishedDate", "crawledAt"));
        Page<CrawledArticle> articlesPage = companyCrawlerService.getArticlesForCompany(companyId, pageable);

        Page<ExternalDataItemResponse> responsePage = articlesPage.map(this::toResponse);
        return ResponseEntity.ok(ApiResponse.success(PageResponse.of(responsePage)));
    }

    @GetMapping("/company-profiles/{companyId}/news")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','BUSINESS_OWNER','BUSINESS_DEVELOPMENT_MANAGER','BUSINESS_DEVELOPMENT_STAFF') and @companyScope.canAccessCompany(#companyId)")
    public ResponseEntity<ApiResponse<PageResponse<ExternalDataItemResponse>>> getCompanyProfileNews(
            @PathVariable String companyId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        return getCompanyArticles(companyId, page, size);
    }

    @PostMapping("/crawler/companies/{companyId}/trigger")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','BUSINESS_DEVELOPMENT_MANAGER','BUSINESS_OWNER','BUSINESS_DEVELOPMENT_STAFF') and @companyScope.canAccessCompany(#companyId)")
    public ResponseEntity<Map<String, Object>> triggerCompanyCrawl(@PathVariable String companyId) {
        log.info("Triggering crawler specifically for companyId: {}", companyId);
        boolean started = companyCrawlerService.triggerCompanyCrawl(companyId);

        if (!started) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "companyId", companyId,
                    "status", "RUNNING",
                    "message", "Crawl cycle is already in progress for this company."
            ));
        }

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "companyId", companyId,
                "status", "RUNNING",
                "message", "Company news crawler triggered successfully."
        ));
    }

    @GetMapping("/crawler/companies/{companyId}/status")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','BUSINESS_DEVELOPMENT_MANAGER','BUSINESS_OWNER','BUSINESS_DEVELOPMENT_STAFF') and @companyScope.canAccessCompany(#companyId)")
    public ResponseEntity<Map<String, Object>> getCompanyCrawlStatus(@PathVariable String companyId) {
        String status = companyCrawlerService.getCrawlStatus(companyId);
        return ResponseEntity.ok(Map.of(
                "companyId", companyId,
                "status", status
        ));
    }

    private ExternalDataItemResponse toResponse(CrawledArticle article) {
        String companyName = (article.getMatchedCompanies() != null && !article.getMatchedCompanies().isEmpty())
                ? article.getMatchedCompanies().get(0).getCompanyName()
                : null;
        String companyId = (article.getMatchedCompanies() != null && !article.getMatchedCompanies().isEmpty())
                ? article.getMatchedCompanies().get(0).getCompanyId()
                : null;

        java.time.LocalDateTime pubDate = null;
        if (article.getPublishedDate() != null) {
            try {
                pubDate = java.time.LocalDate.parse(article.getPublishedDate()).atStartOfDay();
            } catch (Exception ignored) {}
        }

        return ExternalDataItemResponse.builder()
                .id(article.getId())
                .title(article.getTitle())
                .summary(article.getSummary())
                .aiSummary(article.getAiSummary())
                .content(article.getContent())
                .source(article.getSourceName())
                .url(article.getUrl())
                .imageUrl(article.getThumbnail())
                .publishedAt(pubDate != null ? pubDate : article.getCrawledAt())
                .createdAt(article.getCrawledAt())
                .updatedAt(article.getCrawledAt())
                .relatedCompanyName(companyName)
                .relatedCompanyId(companyId)
                .category(com.apms.common.enums.ExternalDataCategory.NEWS)
                .sentiment(article.getSentiment())
                .riskLevel(article.getPriorityLevel())
                .build();
    }
}
