package com.apms.domain.crawler.crawl;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.util.List;

@Component
public class VnEconomyNewsSource extends AbstractRssNewsSourceAdapter {
    public VnEconomyNewsSource(RestClient restClient) {
        super(restClient);
    }

    @Override
    public String getSourceName() {
        return "VnEconomy";
    }

    @Override
    public String getBaseUrl() {
        return "https://vneconomy.vn";
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    protected List<String> getRssUrls() {
        return List.of(
            "https://vneconomy.vn/rss/doanh-nghiep.rss",
            "https://vneconomy.vn/rss/tai-chinh.rss",
            "https://vneconomy.vn/rss/thi-truong.rss"
        );
    }
}
