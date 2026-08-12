package com.apms.domain.crawler.service;

import com.apms.domain.crawler.crawl.CrawlerResult;
import com.apms.domain.crawler.crawl.TargetedNewsCrawler;
import com.apms.domain.crawler.domain.CompanyMatch;
import com.apms.domain.crawler.domain.CrawledArticle;
import com.apms.domain.crawler.domain.TrackedCompany;
import com.apms.domain.crawler.integration.ArticlePublisher;
import com.apms.domain.crawler.repository.CrawledArticleRepository;
import com.apms.domain.crawler.repository.TrackedCompanyRepository;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class CompanyCrawlerService {

    private final TargetedNewsCrawler targetedNewsCrawler;
    private final CrawledArticleRepository articleRepository;
    private final ArticleTriageService articleTriageService;
    private final ArticlePublisher articlePublisher;
    private final CompanyProfileRepository companyProfileRepository;
    private final TrackedCompanyRepository trackedCompanyRepository;
    private final TrackedCompanyCache companyCache;
    private final MongoTemplate mongoTemplate;

    private final Map<String, String> companyCrawlStatus = new ConcurrentHashMap<>();

    public String getCrawlStatus(String companyId) {
        return companyCrawlStatus.getOrDefault(companyId, "IDLE");
    }

    public Page<CrawledArticle> getArticlesForCompany(String companyId, Pageable pageable) {
        // Look up profile or tracked company to gather name aliases
        Optional<CompanyProfile> profileOpt = companyProfileRepository.findById(companyId)
                .or(() -> companyProfileRepository.findByCompanyId(companyId));

        Set<String> searchNames = new HashSet<>();
        searchNames.add(companyId);

        profileOpt.ifPresent(p -> {
            if (p.getIdentity() != null) {
                if (StringUtils.hasText(p.getIdentity().getLegalName())) {
                    searchNames.add(p.getIdentity().getLegalName().trim());
                }
                if (StringUtils.hasText(p.getIdentity().getTradeName())) {
                    searchNames.add(p.getIdentity().getTradeName().trim());
                }
            }
        });

        Optional<TrackedCompany> trackedOpt = trackedCompanyRepository.findById(companyId)
                .or(() -> trackedCompanyRepository.findByCompanyNameIgnoreCase(companyId));

        trackedOpt.ifPresent(tc -> {
            if (StringUtils.hasText(tc.getCompanyName())) {
                searchNames.add(tc.getCompanyName().trim());
            }
            if (tc.getAliases() != null) {
                for (String a : tc.getAliases()) {
                    if (StringUtils.hasText(a)) searchNames.add(a.trim());
                }
            }
        });

        List<Criteria> orCriterias = new ArrayList<>();
        orCriterias.add(Criteria.where("matchedCompanies.companyId").is(companyId));

        for (String name : searchNames) {
            if (!name.equals(companyId) && StringUtils.hasText(name)) {
                orCriterias.add(Criteria.where("matchedCompanies.companyName").regex("^" + Pattern.quote(name) + "$", "i"));
                orCriterias.add(Criteria.where("matchedCompanies.companyName").regex(Pattern.quote(name), "i"));
            }
        }

        Query query = new Query(new Criteria().orOperator(orCriterias.toArray(new Criteria[0])));
        long total = mongoTemplate.count(query, CrawledArticle.class);
        
        query.with(pageable);
        List<CrawledArticle> articles = mongoTemplate.find(query, CrawledArticle.class);

        return new PageImpl<>(articles, pageable, total);
    }

    public boolean triggerCompanyCrawl(String companyId) {
        String currentStatus = getCrawlStatus(companyId);
        if ("RUNNING".equalsIgnoreCase(currentStatus)) {
            log.info("Crawl already running for companyId: {}", companyId);
            return false;
        }

        companyCrawlStatus.put(companyId, "RUNNING");
        runCompanyCrawlAsync(companyId);
        return true;
    }

    @Async("taskExecutor")
    public void runCompanyCrawlAsync(String companyId) {
        log.info("CompanyCrawlerService: Starting async company crawl for ID: {}", companyId);
        try {
            CompanyProfile profile = companyProfileRepository.findById(companyId)
                    .or(() -> companyProfileRepository.findByCompanyId(companyId))
                    .orElse(null);

            TrackedCompany trackedCompany = trackedCompanyRepository.findById(companyId)
                    .or(() -> profile != null && profile.getIdentity() != null && StringUtils.hasText(profile.getIdentity().getLegalName())
                            ? trackedCompanyRepository.findByCompanyNameIgnoreCase(profile.getIdentity().getLegalName().trim())
                            : Optional.empty())
                    .orElse(null);

            if (trackedCompany == null && profile != null && profile.getIdentity() != null) {
                String legalName = profile.getIdentity().getLegalName();
                String tradeName = profile.getIdentity().getTradeName();
                String primaryName = StringUtils.hasText(legalName) ? legalName.trim() : (StringUtils.hasText(tradeName) ? tradeName.trim() : null);

                if (primaryName != null) {
                    List<String> aliases = new ArrayList<>();
                    if (StringUtils.hasText(tradeName) && !tradeName.trim().equalsIgnoreCase(primaryName)) {
                        aliases.add(tradeName.trim());
                    }

                    trackedCompany = TrackedCompany.builder()
                            .id(profile.getId())
                            .companyName(primaryName)
                            .aliases(aliases)
                            .isActive(true)
                            .createdAt(LocalDateTime.now())
                            .updatedAt(LocalDateTime.now())
                            .build();

                    trackedCompany = trackedCompanyRepository.save(trackedCompany);
                    companyCache.forceRefresh();
                    log.info("Created missing TrackedCompany for: {}", primaryName);
                }
            }

            if (trackedCompany == null) {
                log.warn("CompanyCrawlerService: Unable to resolve TrackedCompany for ID: {}", companyId);
                companyCrawlStatus.put(companyId, "FAILED");
                return;
            }

            final String targetTrackedId = trackedCompany.getId();
            final String targetCompName = trackedCompany.getCompanyName();

            // Crawl RSS feeds using targeted crawler for this company only
            CrawlerResult result = targetedNewsCrawler.crawl(List.of(trackedCompany), 10);
            List<CrawledArticle> matched = result.getMatchedArticles();

            int savedCount = 0;
            if (matched != null) {
                for (CrawledArticle article : matched) {
                    // Check duplicate by URL
                    if (articleRepository.existsByUrl(article.getUrl())) {
                        log.debug("Skipping duplicate article URL: {}", article.getUrl());
                        continue;
                    }

                    // Attach company match with exact companyId
                    boolean hasMatch = article.getMatchedCompanies() != null && article.getMatchedCompanies().stream()
                            .anyMatch(m -> companyId.equals(m.getCompanyId()) || targetTrackedId.equals(m.getCompanyId()));

                    if (!hasMatch) {
                        CompanyMatch match = CompanyMatch.builder()
                                .companyId(targetTrackedId)
                                .companyName(targetCompName)
                                .confidenceScore(1.0)
                                .matchType("EXACT")
                                .build();

                        if (article.getMatchedCompanies() == null) {
                            article.setMatchedCompanies(new ArrayList<>());
                        }
                        article.getMatchedCompanies().add(match);
                    }

                    articleRepository.save(articleTriageService.triage(article));
                    savedCount++;
                }
            }

            // Publish matched articles to raw_documents
            articlePublisher.publishMatchedArticles();

            log.info("CompanyCrawlerService: Completed crawl for company '{}' (ID: {}). Saved {} new articles.",
                    trackedCompany.getCompanyName(), companyId, savedCount);

            companyCrawlStatus.put(companyId, "COMPLETED");
        } catch (Exception e) {
            log.error("CompanyCrawlerService: Crawl failed for companyId: {}: {}", companyId, e.getMessage(), e);
            companyCrawlStatus.put(companyId, "FAILED");
        }
    }
}
