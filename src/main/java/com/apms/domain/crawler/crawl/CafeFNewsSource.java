package com.apms.domain.crawler.crawl;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.util.List;

@Component
public class CafeFNewsSource extends AbstractRssNewsSourceAdapter {
    public CafeFNewsSource(RestClient restClient) {
        super(restClient);
    }
    @Override public String getSourceName() { return "CafeF"; }
    @Override public String getBaseUrl() { return "https://cafef.vn"; }
    @Override public boolean isEnabled() { return true; }
    @Override protected List<String> getRssUrls() {
        return List.of(
            "https://cafef.vn/home.rss", 
            "https://cafef.vn/doanh-nghiep.rss",
            "https://cafef.vn/tai-chinh-ngan-hang.rss",
            "https://cafef.vn/thi-truong-chung-khoan.rss",
            "https://cafef.vn/vi-mo-dau-tu.rss",
            "https://cafef.vn/kinh-te-so.rss"
        );
    }
}
