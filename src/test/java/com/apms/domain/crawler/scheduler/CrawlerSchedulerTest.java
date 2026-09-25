package com.apms.domain.crawler.scheduler;

import com.apms.domain.crawler.config.CrawlerConfig;
import com.apms.domain.crawler.crawl.CrawlerResult;
import com.apms.domain.crawler.crawl.TargetedNewsCrawler;
import com.apms.domain.crawler.domain.CompanyMatch;
import com.apms.domain.crawler.domain.CrawledArticle;
import com.apms.domain.crawler.domain.TrackedCompany;
import com.apms.domain.crawler.integration.ArticlePublisher;
import com.apms.domain.crawler.repository.CrawledArticleRepository;
import com.apms.domain.crawler.service.ArticleTriageService;
import com.apms.domain.crawler.service.TrackedCompanyCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class CrawlerSchedulerTest {

    @Mock
    private CrawlerConfig crawlerConfig;
    @Mock
    private TargetedNewsCrawler targetedNewsCrawler;
    @Mock
    private CrawledArticleRepository articleRepository;
    @Mock
    private ArticlePublisher articlePublisher;
    @Mock
    private TrackedCompanyCache companyCache;
    @Mock
    private ArticleTriageService articleTriageService;

    @InjectMocks
    private CrawlerScheduler crawlerScheduler;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(crawlerScheduler, "scheduleEnabled", true);
        ReflectionTestUtils.setField(crawlerScheduler, "autoSummaryEnabled", true);
    }

    @Test
    void testCrawlerRunsAndSkipsDuplicates() {
        TrackedCompany company = new TrackedCompany();
        company.setId("fpt-1");
        company.setCompanyName("FPT");

        when(companyCache.getActiveCompanies()).thenReturn(List.of(company));
        
        CrawledArticle existingArticle = new CrawledArticle();
        existingArticle.setId("existing-1");
        existingArticle.setUrl("https://cafef.vn/article1");
        existingArticle.setMatchedCompanies(new java.util.ArrayList<>());

        CrawledArticle newArticle = new CrawledArticle();
        newArticle.setUrl("https://cafef.vn/article1");
        CompanyMatch match = new CompanyMatch();
        match.setCompanyId("fpt-1");
        newArticle.setMatchedCompanies(List.of(match));

        CrawlerResult result = CrawlerResult.builder()
                .status("COMPLETED")
                .matchedArticles(List.of(newArticle))
                .build();

        when(targetedNewsCrawler.crawl(anyList(), anyInt())).thenReturn(result);
        when(articleRepository.findByUrl("https://cafef.vn/article1")).thenReturn(Optional.of(existingArticle));

        crawlerScheduler.runCrawlPipeline();

        verify(articleRepository, never()).save(newArticle); 
        // Existing article should be saved because it got a new match relation merged
        verify(articleRepository, times(1)).save(existingArticle);
        assert existingArticle.getMatchedCompanies().size() == 1;
    }
}
