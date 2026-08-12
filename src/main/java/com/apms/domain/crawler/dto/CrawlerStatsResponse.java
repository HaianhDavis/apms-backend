package com.apms.domain.crawler.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Response DTO for crawler statistics.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CrawlerStatsResponse {

    private long totalArticles;
    private long pendingArticles;
    private long matchedArticles;
    private long discardedArticles;
    private long publishedArticles;
    private long errorArticles;
    private int trackedCompanies;
    private int configuredFeeds;
    private LocalDateTime lastUpdatedAt;
}
