package com.apms.domain.crawler.crawl;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.util.List;

@Component
public class ThanhNienNewsSource extends AbstractRssNewsSourceAdapter {
    public ThanhNienNewsSource(RestClient restClient) {
        super(restClient);
    }
    @Override public String getSourceName() { return "ThanhNien"; }
    @Override public String getBaseUrl() { return "https://thanhnien.vn"; }
    @Override public boolean isEnabled() { return true; }
    @Override protected List<String> getRssUrls() {
        return List.of(
            "https://thanhnien.vn/rss/home.rss", 
            "https://thanhnien.vn/rss/kinh-te.rss",
            "https://thanhnien.vn/rss/kinh-te/doanh-nghiep.rss",
            "https://thanhnien.vn/rss/cong-nghe.rss"
        );
    }
}
