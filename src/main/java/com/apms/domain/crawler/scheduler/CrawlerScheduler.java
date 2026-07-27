package com.apms.domain.crawler.scheduler;

import com.apms.domain.crawler.ai.CompanyDetectionService;
import com.apms.domain.crawler.ai.GeminiArticleSummarizer;
import com.apms.domain.crawler.config.CrawlerConfig;
import com.apms.domain.crawler.crawl.TargetedNewsCrawler;
import com.apms.domain.crawler.domain.CrawledArticle;
import com.apms.domain.crawler.domain.TrackedCompany;
import com.apms.domain.crawler.integration.ArticlePublisher;
import com.apms.domain.crawler.repository.CrawledArticleRepository;
import com.apms.domain.crawler.service.ArticleTriageService;
import com.apms.domain.crawler.service.TrackedCompanyCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
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

    @Value("${crawler.schedule.enabled:true}")
    private boolean automaticCrawlerEnabled;

    @Value("${crawler.startup.enabled:true}")
    private boolean startupCrawlEnabled;

    @EventListener(ApplicationReadyEvent.class)
    public void runOnStartup() {
        if (!automaticCrawlerEnabled) {
            log.info("CrawlerScheduler: Automatic crawler disabled.");
            return;
        }
        if (!startupCrawlEnabled) {
            log.info("CrawlerScheduler: Startup crawl disabled.");
            return;
        }
        log.info("CrawlerScheduler: Application ready. Triggering startup crawl.");
        triggerManualCrawl();
    }

    private final AtomicBoolean running = new AtomicBoolean(false);

    @Scheduled(cron = "${crawler.schedule.cron:0 */30 * * * *}")
    public void runCrawlPipeline() {
        if (!automaticCrawlerEnabled) {
            log.debug("CrawlerScheduler: Skipping scheduled crawl because automatic crawler is disabled.");
            return;
        }
        runPipeline();
    }

    private void runPipeline() {
        if (!running.compareAndSet(false, true)) {
            log.warn("CrawlerScheduler: Previous crawl cycle still running. Skipping.");
            return;
        }

        try {
            log.info("CrawlerScheduler: Starting targeted crawl pipeline");

            long startTime = System.currentTimeMillis();
            int totalCrawled = crawlAllFeeds();
            int totalMatched = companyDetectionService.processAllPending();
            int totalTriaged = articleTriageService.backfillMissingTriage();
            int totalSummarized = autoSummaryEnabled ? articleSummarizer.summarizeReadyArticles() : 0;
            int totalPublished = articlePublisher.publishMatchedArticles();
            long elapsed = System.currentTimeMillis() - startTime;

            long totalPending = articleRepository.countByAiProcessingStatus("PENDING");
            long totalDiscarded = articleRepository.countByAiProcessingStatus("DISCARDED");
            long totalErrors = articleRepository.countByAiProcessingStatus("ERROR");

            log.info("CrawlerScheduler: Pipeline completed in {}ms", elapsed);
            log.info("  Crawled relevant new articles: {}", totalCrawled);
            log.info("  Matched: {}", totalMatched);
            log.info("  Triaged: {}", totalTriaged);
            log.info("  AI summarized: {}", totalSummarized);
            log.info("  Published: {}", totalPublished);
            log.info("  Still pending: {}", totalPending);
            log.info("  Discarded: {}", totalDiscarded);
            log.info("  Errors: {}", totalErrors);
        } catch (Exception e) {
            log.error("CrawlerScheduler: Pipeline failed: {}", e.getMessage(), e);
        } finally {
            running.set(false);
        }
    }

    public void triggerManualCrawl() {
        new Thread(() -> {
            log.info("CrawlerScheduler: Manual crawl triggered.");
            runPipeline();
        }, "manual-crawl").start();
    }

    private int crawlAllFeeds() {
        List<TrackedCompany> trackedCompanies = companyCache.getActiveCompanies();
        if (trackedCompanies.isEmpty()) {
            log.warn("CrawlerScheduler: No active target companies configured.");
            return 0;
        }

        int totalNew = 0;
        List<CrawledArticle> articles = targetedNewsCrawler.crawl(trackedCompanies, maxArticlesPerCompanyPerSource());
        int duplicateCount = 0;
        int thumbnailUpdatedCount = 0;

        for (CrawledArticle article : articles) {
            var existingArticle = articleRepository.findByUrl(article.getUrl());
            if (existingArticle.isPresent()) {
                duplicateCount++;
                CrawledArticle existing = existingArticle.get();
                if ((existing.getThumbnail() == null || existing.getThumbnail().isBlank()) &&
                        article.getThumbnail() != null && !article.getThumbnail().isBlank()) {
                    existing.setThumbnail(article.getThumbnail());
                    articleRepository.save(existing);
                    thumbnailUpdatedCount++;
                }
                log.debug("CrawlerScheduler: Skipping duplicate: {}", article.getUrl());
                continue;
            }

            articleRepository.save(articleTriageService.triage(article));
            totalNew++;
        }

        log.info("CrawlerScheduler: Targeted search produced {}, saved pending {}, skipped duplicate {}, updated thumbnails {}",
                articles.size(), totalNew, duplicateCount, thumbnailUpdatedCount);
        return totalNew;
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

