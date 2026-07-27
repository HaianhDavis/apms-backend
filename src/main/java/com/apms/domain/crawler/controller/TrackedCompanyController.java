package com.apms.domain.crawler.controller;

import com.apms.domain.crawler.config.CrawlerConfig;
import com.apms.domain.crawler.ai.GeminiArticleSummarizer;
import com.apms.domain.crawler.domain.CrawledArticle;
import com.apms.domain.crawler.domain.TrackedCompany;
import com.apms.domain.crawler.dto.CrawlerStatsResponse;
import com.apms.domain.crawler.dto.TrackedCompanyRequest;
import com.apms.domain.crawler.dto.TrackedCompanyResponse;
import com.apms.domain.crawler.repository.CrawledArticleRepository;
import com.apms.domain.crawler.repository.TrackedCompanyRepository;
import com.apms.domain.crawler.scheduler.CrawlerScheduler;
import com.apms.domain.crawler.service.TrackedCompanyCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST API for managing tracked companies and controlling the crawler.
 *
 * Endpoints:
 * - GET    /api/v1/tracked-companies          â€” list all tracked companies
 * - POST   /api/v1/tracked-companies          â€” add a new tracked company
 * - PUT    /api/v1/tracked-companies/{id}     â€” update a tracked company
 * - DELETE /api/v1/tracked-companies/{id}     â€” soft-delete (deactivate)
 * - POST   /api/v1/crawler/trigger            â€” manually trigger crawl cycle
 * - GET    /api/v1/crawler/stats              â€” crawl statistics
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class TrackedCompanyController {

    private final TrackedCompanyRepository trackedCompanyRepository;
    private final CrawledArticleRepository crawledArticleRepository;
    private final TrackedCompanyCache companyCache;
    private final CrawlerScheduler crawlerScheduler;
    private final CrawlerConfig crawlerConfig;
    private final GeminiArticleSummarizer articleSummarizer;

    @GetMapping("/tracked-companies")
    public ResponseEntity<List<TrackedCompanyResponse>> listAll(
            @RequestParam(required = false, defaultValue = "false") boolean activeOnly) {

        List<TrackedCompany> companies = activeOnly
                ? trackedCompanyRepository.findByIsActiveTrue()
                : trackedCompanyRepository.findAll();

        List<TrackedCompanyResponse> responses = companies.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(responses);
    }

    @GetMapping("/tracked-companies/{id}")
    public ResponseEntity<TrackedCompanyResponse> getById(@PathVariable String id) {
        return trackedCompanyRepository.findById(id)
                .map(this::toResponse)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/tracked-companies")
    public ResponseEntity<?> create(@RequestBody TrackedCompanyRequest request) {
        if (request.getCompanyName() == null || request.getCompanyName().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Company name is required."));
        }

        if (trackedCompanyRepository.existsByCompanyNameIgnoreCase(request.getCompanyName())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Company '" + request.getCompanyName() + "' already exists."));
        }

        TrackedCompany company = TrackedCompany.builder()
                .companyName(request.getCompanyName().trim())
                .aliases(request.getAliases() != null ? request.getAliases() : new ArrayList<>())
                .subsidiaries(request.getSubsidiaries() != null ? request.getSubsidiaries() : new ArrayList<>())
                .products(request.getProducts() != null ? request.getProducts() : new ArrayList<>())
                .keyPeople(request.getKeyPeople() != null ? request.getKeyPeople() : new ArrayList<>())
                .industry(request.getIndustry())
                .isActive(true)
                .build();

        TrackedCompany saved = trackedCompanyRepository.save(company);
        companyCache.forceRefresh();

        log.info("TrackedCompany created: '{}'", saved.getCompanyName());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(saved));
    }

    @PutMapping("/tracked-companies/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody TrackedCompanyRequest request) {
        return trackedCompanyRepository.findById(id)
                .map(existing -> {
                    if (request.getCompanyName() != null && !request.getCompanyName().isBlank()) {
                        existing.setCompanyName(request.getCompanyName().trim());
                    }
                    if (request.getAliases() != null) {
                        existing.setAliases(request.getAliases());
                    }
                    if (request.getSubsidiaries() != null) {
                        existing.setSubsidiaries(request.getSubsidiaries());
                    }
                    if (request.getProducts() != null) {
                        existing.setProducts(request.getProducts());
                    }
                    if (request.getKeyPeople() != null) {
                        existing.setKeyPeople(request.getKeyPeople());
                    }
                    if (request.getIndustry() != null) {
                        existing.setIndustry(request.getIndustry());
                    }

                    TrackedCompany saved = trackedCompanyRepository.save(existing);
                    companyCache.forceRefresh();

                    log.info("TrackedCompany updated: '{}'", saved.getCompanyName());
                    return ResponseEntity.ok(toResponse(saved));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/tracked-companies/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        return trackedCompanyRepository.findById(id)
                .map(existing -> {
                    existing.setIsActive(false);
                    trackedCompanyRepository.save(existing);
                    companyCache.forceRefresh();

                    log.info("TrackedCompany deactivated: '{}'", existing.getCompanyName());
                    return ResponseEntity.ok(Map.of(
                            "message", "Company '" + existing.getCompanyName() + "' deactivated.",
                            "id", id));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // Crawler Control
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    @PostMapping("/crawler/trigger")
    public ResponseEntity<Map<String, String>> triggerCrawl() {
        log.info("Manual crawl triggered via REST API.");
        crawlerScheduler.triggerManualCrawl();
        return ResponseEntity.accepted()
                .body(Map.of("message", "Crawl pipeline triggered. Check logs for progress."));
    }

    @GetMapping("/crawler/stats")
    public ResponseEntity<CrawlerStatsResponse> getStats() {
        CrawlerStatsResponse stats = CrawlerStatsResponse.builder()
                .totalArticles(crawledArticleRepository.count())
                .pendingArticles(crawledArticleRepository.countByAiProcessingStatus("PENDING"))
                .matchedArticles(crawledArticleRepository.countByAiProcessingStatus("MATCHED"))
                .discardedArticles(crawledArticleRepository.countByAiProcessingStatus("DISCARDED"))
                .publishedArticles(crawledArticleRepository.countByAiProcessingStatus("PUBLISHED"))
                .errorArticles(crawledArticleRepository.countByAiProcessingStatus("ERROR"))
                .trackedCompanies(companyCache.size())
                .configuredFeeds(crawlerConfig.getFeeds() != null ? crawlerConfig.getFeeds().size() : 0)
                .lastUpdatedAt(crawledArticleRepository.findTopByOrderByCrawledAtDesc()
                        .map(CrawledArticle::getCrawledAt)
                        .orElse(null))
                .build();

        return ResponseEntity.ok(stats);
    }

    @GetMapping("/crawler/articles")
    public ResponseEntity<Page<CrawledArticle>> listArticles(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String company,
            @RequestParam(required = false) String source,
            @RequestParam(required = false, defaultValue = "false") boolean hot,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {

        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        Pageable pageable = PageRequest.of(
                safePage,
                safeSize,
                Sort.by(Sort.Direction.DESC, "crawledAt", "publishedDate"));

        boolean hasStatus = status != null && !status.isBlank();
        boolean hasCompany = company != null && !company.isBlank();
        boolean hasSource = source != null && !source.isBlank();

        Page<CrawledArticle> articles;
        if (hot && hasStatus && !hasCompany && !hasSource) {
            articles = crawledArticleRepository.findByAiProcessingStatusOrderByPriorityScoreDescCrawledAtDesc(
                    status.trim().toUpperCase(),
                    pageable);
        } else if (hasStatus && hasCompany && hasSource) {
            articles = crawledArticleRepository.findByAiProcessingStatusAndMatchedCompaniesCompanyNameIgnoreCaseAndSourceNameIgnoreCase(
                    status.trim().toUpperCase(),
                    company.trim(),
                    source.trim(),
                    pageable);
        } else if (hasStatus && hasCompany) {
            articles = crawledArticleRepository.findByAiProcessingStatusAndMatchedCompaniesCompanyNameIgnoreCase(
                    status.trim().toUpperCase(),
                    company.trim(),
                    pageable);
        } else if (hasStatus && hasSource) {
            articles = crawledArticleRepository.findByAiProcessingStatusAndSourceNameIgnoreCase(
                    status.trim().toUpperCase(),
                    source.trim(),
                    pageable);
        } else if (hasCompany && hasSource) {
            articles = crawledArticleRepository.findByMatchedCompaniesCompanyNameIgnoreCaseAndSourceNameIgnoreCase(
                    company.trim(),
                    source.trim(),
                    pageable);
        } else if (hasStatus) {
            articles = crawledArticleRepository.findByAiProcessingStatus(status.trim().toUpperCase(), pageable);
        } else if (hasCompany) {
            articles = crawledArticleRepository.findByMatchedCompaniesCompanyNameIgnoreCase(company.trim(), pageable);
        } else if (hasSource) {
            articles = crawledArticleRepository.findBySourceNameIgnoreCase(source.trim(), pageable);
        } else {
            articles = crawledArticleRepository.findAll(pageable);
        }

        return ResponseEntity.ok(articles);
    }

    @GetMapping("/crawler/articles/{id}")
    public ResponseEntity<CrawledArticle> getArticleById(@PathVariable String id) {
        return crawledArticleRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/crawler/articles/{id}/summarize")
    public ResponseEntity<?> summarizeArticle(@PathVariable String id,
                                              @RequestParam(required = false, defaultValue = "false") boolean refresh) {
        return crawledArticleRepository.findById(id)
                .map(article -> {
                    try {
                        CrawledArticle summarized = articleSummarizer.summarizeArticle(article, refresh);
                        return ResponseEntity.ok(summarized);
                    } catch (Exception e) {
                        log.warn("Failed to summarize article '{}': {}", id, e.getMessage());
                        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
                    }
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
    // Helpers
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    private TrackedCompanyResponse toResponse(TrackedCompany company) {
        return TrackedCompanyResponse.builder()
                .id(company.getId())
                .companyName(company.getCompanyName())
                .aliases(company.getAliases())
                .subsidiaries(company.getSubsidiaries())
                .products(company.getProducts())
                .keyPeople(company.getKeyPeople())
                .industry(company.getIndustry())
                .isActive(company.getIsActive())
                .createdAt(company.getCreatedAt())
                .updatedAt(company.getUpdatedAt())
                .build();
    }
}

