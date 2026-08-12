package com.apms.domain.crawler.crawl;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.util.List;

@Component
public class TuoiTreNewsSource extends AbstractRssNewsSourceAdapter {
    public TuoiTreNewsSource(RestClient restClient) {
        super(restClient);
    }
    @Override public String getSourceName() { return "TuoiTre"; }
    @Override public String getBaseUrl() { return "https://tuoitre.vn"; }
    @Override public boolean isEnabled() { return true; }
    @Override protected List<String> getRssUrls() {
        return List.of("https://tuoitre.vn/rss/tin-moi-nhat.rss", "https://tuoitre.vn/rss/kinh-doanh.rss");
    }
}
