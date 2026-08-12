package com.apms.domain.crawler.crawl;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.util.List;

@Component
public class VnExpressNewsSource extends AbstractRssNewsSourceAdapter {
    public VnExpressNewsSource(RestClient restClient) {
        super(restClient);
    }
    @Override public String getSourceName() { return "VnExpress"; }
    @Override public String getBaseUrl() { return "https://vnexpress.net"; }
    @Override public boolean isEnabled() { return true; }
    @Override protected List<String> getRssUrls() {
        return List.of("https://vnexpress.net/rss/tin-moi-nhat.rss", "https://vnexpress.net/rss/kinh-doanh.rss");
    }
}
