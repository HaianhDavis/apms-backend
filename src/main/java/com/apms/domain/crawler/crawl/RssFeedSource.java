package com.apms.domain.crawler.crawl;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Configuration model for a single RSS feed source.
 * Loaded from application.properties via crawler.feeds[n] prefix.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RssFeedSource {

    /** Human-readable source name, e.g. "CafeF", "TechCrunch" */
    private String name;

    /** RSS feed URL */
    private String url;

    /** Content language: "vi" or "en" */
    private String language;

    /** Maximum number of articles to fetch from this feed per crawl cycle */
    private int maxArticles = 10;
}
