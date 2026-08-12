package com.apms.domain.crawler.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.apms.domain.crawler.crawl.RssFeedSource;
import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.config.EnableMongoAuditing;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Central configuration for the Crawler microservice.
 * Provides shared beans and loads RSS feed sources from application.properties.
 */
@Configuration
@EnableMongoAuditing
@ConfigurationProperties(prefix = "crawler")
@Data
public class CrawlerConfig {

    /**
     * RSS feed sources loaded from application.properties.
     * Each entry maps to crawler.feeds[n].{name, url, language, maxArticles}.
     */
    private List<RssFeedSource> feeds = new ArrayList<>();

    /**
     * RestClient bean for HTTP calls (RSS feeds, Gemini API, article fetching).
     */
    @Bean
    public RestClient restClient() {
        return RestClient.builder()
                .defaultHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36")
                .defaultHeader("Accept", "application/rss+xml, application/xml, text/xml, text/html, */*")
                .defaultHeader("Accept-Language", "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7")
                .build();
    }


}
