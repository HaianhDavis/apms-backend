package com.apms.domain.crawler.crawl;

import com.apms.domain.crawler.domain.CrawledArticle;
import com.apms.domain.crawler.domain.TrackedCompany;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class TargetedNewsCrawler {

    private final List<NewsSourceAdapter> sourceAdapters;
    private final CompanyArticleMatcher companyArticleMatcher;

    public TargetedNewsCrawler(List<NewsSourceAdapter> sourceAdapters, CompanyArticleMatcher companyArticleMatcher) {
        this.sourceAdapters = sourceAdapters;
        this.companyArticleMatcher = companyArticleMatcher;
    }

    public CrawlerResult crawl(List<TrackedCompany> trackedCompanies, int ignoredMaxArticles) {
        log.info("TargetedNewsCrawler: Starting direct-source crawl");
        
        List<CrawledArticle> allFetchedArticles = new ArrayList<>();
        Map<String, CrawledArticle> articlesByUrl = new LinkedHashMap<>();
        List<SourceFetchResult> sourceResults = new ArrayList<>();
        
        int sourcesAttempted = 0;
        int sourcesSucceeded = 0;
        int sourcesFailed = 0;

        for (NewsSourceAdapter source : sourceAdapters) {
            if (!source.isEnabled()) continue;
            sourcesAttempted++;
            
            try {
                log.info("Fetching {}", source.getSourceName());
                SourceFetchResult sourceResult = source.fetchLatestArticles();
                sourceResults.add(sourceResult);
                
                if ("SUCCESS".equals(sourceResult.getStatus())) {
                    sourcesSucceeded++;
                } else if ("PARTIAL_SUCCESS".equals(sourceResult.getStatus())) {
                    sourcesSucceeded++; // count as success for overall metrics
                } else {
                    sourcesFailed++;
                }
                
                for (CrawledArticle article : sourceResult.getArticles()) {
                    if (!articlesByUrl.containsKey(article.getUrl())) {
                        articlesByUrl.put(article.getUrl(), article);
                        allFetchedArticles.add(article);
                    }
                }
            } catch (Exception e) {
                log.warn("TargetedNewsCrawler: Source crawl failed source={} msg={}", 
                        source.getSourceName(), e.getMessage());
                sourcesFailed++;
                sourceResults.add(SourceFetchResult.builder()
                        .sourceName(source.getSourceName())
                        .status("FAILED")
                        .errorCode("UNEXPECTED_ERROR")
                        .errorMessage(e.getMessage())
                        .build());
            }
        }

        log.info("Matching tracked companies...");
        
        log.info("--- TRACKED COMPANIES DIAGNOSTIC ---");
        for (int i = 0; i < trackedCompanies.size(); i++) {
            TrackedCompany c = trackedCompanies.get(i);
            log.info("{}. companyProfileId={} legalName='{}' aliases={} subsidiaries={} products={} keyPeople={}", 
                     (i + 1), c.getId(), c.getCompanyName(), c.getAliases(), c.getSubsidiaries(), c.getProducts(), c.getKeyPeople());
        }
        log.info("------------------------------------");

        List<CrawledArticle> matchedArticles = companyArticleMatcher.matchAndAssign(allFetchedArticles, trackedCompanies);
        
        for (TrackedCompany company : trackedCompanies) {
            long count = matchedArticles.stream()
                .filter(a -> a.getMatchedCompanies() != null && a.getMatchedCompanies().stream().anyMatch(m -> m.getCompanyId().equals(company.getId())))
                .count();
            if (count > 0) {
                log.info("{}: {} related articles", company.getCompanyName(), count);
            }
        }
        
        String overallStatus = "COMPLETED";
        if (sourcesFailed > 0 && sourcesSucceeded > 0) {
            overallStatus = "PARTIAL_SUCCESS";
        } else if (sourcesFailed > 0 && sourcesSucceeded == 0) {
            overallStatus = "FAILED";
        }

        return CrawlerResult.builder()
                .status(overallStatus)
                .sourcesAttempted(sourcesAttempted)
                .sourcesSucceeded(sourcesSucceeded)
                .sourcesFailed(sourcesFailed)
                .articlesFetched(allFetchedArticles.size())
                .articlesMatched(matchedArticles.size())
                .matchedArticles(matchedArticles)
                .sourceResults(sourceResults)
                .build();
    }
}
