package com.apms.domain.crawler.crawl;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.util.List;

@Component
public class VietnamBizNewsSource extends AbstractRssNewsSourceAdapter {
    public VietnamBizNewsSource(RestClient restClient) {
        super(restClient);
    }

    @Override
    public String getSourceName() {
        return "VietnamBiz";
    }

    @Override
    public String getBaseUrl() {
        return "https://vietnambiz.vn";
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    protected List<String> getRssUrls() {
        return List.of(
            "https://vietnambiz.vn/rss/doanh-nghiep.rss",
            "https://vietnambiz.vn/rss/tai-chinh-ngan-hang.rss",
            "https://vietnambiz.vn/rss/thi-truong.rss"
        );
    }
}
