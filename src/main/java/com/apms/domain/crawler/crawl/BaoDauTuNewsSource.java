package com.apms.domain.crawler.crawl;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.util.List;

@Component
public class BaoDauTuNewsSource extends AbstractRssNewsSourceAdapter {
    public BaoDauTuNewsSource(RestClient restClient) {
        super(restClient);
    }
    @Override public String getSourceName() { return "BaoDauTu"; }
    @Override public String getBaseUrl() { return "https://baodautu.vn"; }
    @Override public boolean isEnabled() { return true; }
    @Override protected List<String> getRssUrls() {
        return List.of("https://baodautu.vn/tin-moi-nhat.rss", "https://baodautu.vn/rss/dau-tu.rss");
    }
}
