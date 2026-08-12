package com.apms.domain.crawler.crawl;

import com.apms.domain.crawler.domain.CrawledArticle;
import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class CrawlerResult {
    private String status; // COMPLETED, PARTIAL_SUCCESS, FAILED
    private int sourcesAttempted;
    private int sourcesSucceeded;
    private int sourcesFailed;
    private int articlesFetched;
    private int articlesMatched;
    private int newArticles;
    private List<CrawledArticle> matchedArticles;
    private List<SourceFetchResult> sourceResults;
}
