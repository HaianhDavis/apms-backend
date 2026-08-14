package com.apms.domain.crawler.scheduler;

import com.apms.domain.crawler.ai.CompanyDetectionService;
import com.apms.domain.crawler.ai.GeminiArticleSummarizer;
import com.apms.domain.crawler.config.CrawlerConfig;
import com.apms.domain.crawler.crawl.CrawlerResult;
import com.apms.domain.crawler.crawl.TargetedNewsCrawler;
import com.apms.domain.crawler.domain.CrawledArticle;
import com.apms.domain.crawler.domain.TrackedCompany;
import com.apms.domain.crawler.integration.ArticlePublisher;
import com.apms.domain.crawler.repository.CrawledArticleRepository;
import com.apms.domain.crawler.service.ArticleTriageService;
import com.apms.domain.crawler.service.TrackedCompanyCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class CrawlerScheduler {

    private final CrawlerConfig crawlerConfig;
    private final TargetedNewsCrawler targetedNewsCrawler;
    private final CrawledArticleRepository articleRepository;
    private final CompanyDetectionService companyDetectionService;
    private final GeminiArticleSummarizer articleSummarizer;
    private final ArticlePublisher articlePublisher;
    private final TrackedCompanyCache companyCache;
    private final ArticleTriageService articleTriageService;

    @Value("${crawler.ai.summary.auto-enabled:false}")
    private boolean autoSummaryEnabled;

    @Value("${crawler.startup.enabled:true}")
    private boolean startupCrawlEnabled;

    @Value("${news.crawler.scheduler.enabled:true}")
    private boolean scheduleEnabled;

    @EventListener(ApplicationReadyEvent.class)
    public void runOnStartup() {
        if (!startupCrawlEnabled) {
            log.info("CrawlerScheduler: Startup crawl disabled.");
            return;
        }
        log.info("CrawlerScheduler: Application ready. Triggering startup crawl.");
        triggerManualCrawl();
    }

    private final AtomicBoolean running = new AtomicBoolean(false);

    @Scheduled(
            fixedDelayString = "#{${news.crawler.scheduler.interval-minutes:30} * 60000}",
            initialDelayString = "#{${news.crawler.scheduler.initial-delay-seconds:30} * 1000}"
    )
    public void runCrawlPipeline() {
        if (!scheduleEnabled) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.warn("CrawlerScheduler: Previous crawl cycle still running. Skipping.");
            return;
        }

        try {
            log.info("CrawlerScheduler: Starting targeted crawl pipeline");

            long startTime = System.currentTimeMillis();
            CrawlerResult crawlerResult = crawlAllFeeds();
            int totalNew = crawlerResult != null ? crawlerResult.getNewArticles() : 0;
            int totalMatched = companyDetectionService.processAllPending();
            int totalTriaged = articleTriageService.backfillMissingTriage();
            int totalSummarized = autoSummaryEnabled ? articleSummarizer.summarizeReadyArticles() : 0;
            int totalPublished = articlePublisher.publishMatchedArticles();
            long elapsed = System.currentTimeMillis() - startTime;

            long totalPending = articleRepository.countByAiProcessingStatus("PENDING");
            long totalDiscarded = articleRepository.countByAiProcessingStatus("DISCARDED");
            long totalErrors = articleRepository.countByAiProcessingStatus("ERROR");

            log.info("Pipeline completed\nCrawled relevant new articles: {}\nMatched: {}\nTriaged: {}\nPublished: {}\nErrors: {}", 
                     totalNew, crawlerResult != null ? crawlerResult.getArticlesMatched() : 0, totalTriaged, totalPublished, totalErrors);
            
            if (crawlerResult != null) {
                log.info("Manual crawl completed:\nstatus={}\nnewArticles={}\nsourcesAttempted={}\nsourcesSucceeded={}\nsourcesFailed={}\nfetched={}\nmatched={}", 
                         crawlerResult.getStatus(),
                         totalNew,
                         crawlerResult.getSourcesAttempted(),
                         crawlerResult.getSourcesSucceeded(),
                         crawlerResult.getSourcesFailed(),
                         crawlerResult.getArticlesFetched(),
                         crawlerResult.getArticlesMatched());
            }
        } catch (Exception e) {
            log.error("CrawlerScheduler: Pipeline failed: {}", e.getMessage(), e);
        } finally {
            running.set(false);
        }
    }

    @Async("taskExecutor")
    public void triggerManualCrawl() {
        log.info("CrawlerScheduler: Async manual crawl triggered.");
        runCrawlPipeline();
    }

    private CrawlerResult crawlAllFeeds() {
        List<TrackedCompany> trackedCompanies = companyCache.getActiveCompanies();
        if (trackedCompanies.isEmpty()) {
            log.warn("CrawlerScheduler: No active target companies configured.");
            return CrawlerResult.builder().status("FAILED").build();
        }

        int totalNew = 0;
        CrawlerResult crawlerResult = targetedNewsCrawler.crawl(trackedCompanies, maxArticlesPerCompanyPerSource());
        int duplicateCount = 0;
        int thumbnailUpdatedCount = 0;

        List<CrawledArticle> matchedArticles = crawlerResult.getMatchedArticles();
        if (matchedArticles == null) {
            return crawlerResult;
        }

        for (CrawledArticle article : matchedArticles) {
            var existingArticle = articleRepository.findByUrl(article.getUrl());
            if (existingArticle.isPresent()) {
                duplicateCount++;
                CrawledArticle existing = existingArticle.get();
                boolean updated = false;

                if ((existing.getThumbnail() == null || existing.getThumbnail().isBlank()) &&
                        article.getThumbnail() != null && !article.getThumbnail().isBlank()) {
                    existing.setThumbnail(article.getThumbnail());
                    thumbnailUpdatedCount++;
                    updated = true;
                }

                if (article.getMatchedCompanies() != null && !article.getMatchedCompanies().isEmpty()) {
                    List<com.apms.domain.crawler.domain.CompanyMatch> currentMatches = existing.getMatchedCompanies();
                    if (currentMatches == null) {
                        currentMatches = new java.util.ArrayList<>();
                        existing.setMatchedCompanies(currentMatches);
                    }
                    for (com.apms.domain.crawler.domain.CompanyMatch newMatch : article.getMatchedCompanies()) {
                        boolean hasMatch = currentMatches.stream()
                                .anyMatch(m -> m.getCompanyId().equals(newMatch.getCompanyId()));
                        if (!hasMatch) {
                            currentMatches.add(newMatch);
                            updated = true;
                        }
                    }
                }

                if (updated) {
                    articleRepository.save(existing);
                }

                log.debug("CrawlerScheduler: Skipping duplicate: {}", article.getUrl());
                continue;
            }

            articleRepository.save(articleTriageService.triage(article));
            totalNew++;
        }

        log.info("CrawlerScheduler: Targeted search produced {}, saved pending {}, skipped duplicate {}, updated thumbnails {}",
                matchedArticles.size(), totalNew, duplicateCount, thumbnailUpdatedCount);
                
        crawlerResult.setNewArticles(totalNew);
        return crawlerResult;
    }

    private int maxArticlesPerCompanyPerSource() {
        if (crawlerConfig.getFeeds() == null || crawlerConfig.getFeeds().isEmpty()) {
            return 5;
        }
        return crawlerConfig.getFeeds().stream()
                .mapToInt(feed -> Math.max(feed.getMaxArticles(), 1))
                .max()
                .orElse(5);
    }
}
