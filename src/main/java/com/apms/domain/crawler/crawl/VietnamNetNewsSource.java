package com.apms.domain.crawler.crawl;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.util.List;

@Component
public class VietnamNetNewsSource extends AbstractRssNewsSourceAdapter {
    public VietnamNetNewsSource(RestClient restClient) {
        super(restClient);
    }
    @Override public String getSourceName() { return "VietnamNet"; }
    @Override public String getBaseUrl() { return "https://vietnamnet.vn"; }
    @Override public boolean isEnabled() { return false; }
    @Override protected List<String> getRssUrls() {
        return List.of("https://vietnamnet.vn/rss/tin-moi-nhat.rss", "https://vietnamnet.vn/rss/kinh-doanh.rss");
    }
}
