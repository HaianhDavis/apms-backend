package com.apms.domain.crawler.crawl;

import java.util.List;

public interface NewsSourceAdapter {
    String getSourceName();
    String getBaseUrl();
    boolean isEnabled();
    SourceFetchResult fetchLatestArticles();
}
