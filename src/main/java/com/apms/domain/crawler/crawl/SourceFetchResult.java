package com.apms.domain.crawler.crawl;

import com.apms.domain.crawler.domain.CrawledArticle;
import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class SourceFetchResult {
    private String sourceName;
    private String status; // SUCCESS, PARTIAL_SUCCESS, or FAILED
    private int fetchedCount;
    private String errorCode;
    private String errorMessage;
    private List<CrawledArticle> articles;
}
