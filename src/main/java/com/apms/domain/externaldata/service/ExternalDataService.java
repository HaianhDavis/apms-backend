package com.apms.domain.externaldata.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.ExternalDataCategory;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.externaldata.ExternalDataItem;
import com.apms.domain.externaldata.dto.ExternalDataItemResponse;
import com.apms.domain.externaldata.repository.mongo.ExternalDataRepository;
import com.apms.domain.crawler.domain.CrawledArticle;
import com.apms.domain.crawler.domain.CompanyMatch;
import com.apms.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExternalDataService {

    private final ExternalDataRepository externalDataRepository;
    private final MongoTemplate mongoTemplate;
    private final AuditLogService auditLogService;
    private final com.apms.domain.crawler.repository.TrackedCompanyRepository trackedCompanyRepository;

    public Page<ExternalDataItemResponse> getExternalData(
            ExternalDataCategory category,
            String keyword,
            String source,
            String companyName,
            LocalDateTime fromDate,
            LocalDateTime toDate,
            String sentiment,
            String importance,
            Pageable pageable) {

        Criteria criteria = new Criteria();

        // category is ignored because CrawledArticle does not have a category field.

        if (StringUtils.hasText(source)) {
            criteria.and("sourceName").is(source);
        }

        if (StringUtils.hasText(companyName)) {
            List<Criteria> orCriterias = new ArrayList<>();
            orCriterias.add(Criteria.where("matchedCompanies.companyName").regex(companyName.trim(), "i"));
            
            trackedCompanyRepository.findByCompanyNameIgnoreCase(companyName.trim()).ifPresent(tc -> {
                if (tc.getAliases() != null) {
                    for (String alias : tc.getAliases()) {
                        orCriterias.add(Criteria.where("matchedCompanies.companyName").regex(alias.trim(), "i"));
                    }
                }
            });
            
            criteria.andOperator(new Criteria().orOperator(orCriterias.toArray(new Criteria[0])));
        }

        if (StringUtils.hasText(keyword)) {
            criteria.orOperator(
                    Criteria.where("title").regex(keyword, "i"),
                    Criteria.where("summary").regex(keyword, "i")
            );
        }

        if (fromDate != null) {
            criteria.and("publishedDate").gte(fromDate.toString());
        }
        if (toDate != null) {
            criteria.andOperator(Criteria.where("publishedDate").lte(toDate.toString()));
        }
        
        if (StringUtils.hasText(sentiment)) {
            criteria.and("sentiment").is(sentiment.toUpperCase());
        }
        
        if (StringUtils.hasText(importance)) {
            criteria.and("priorityLevel").is(importance.toUpperCase());
        }

        Sort sort = pageable.getSort();
        if (sort.getOrderFor("publishedAt") != null) {
            Sort.Order order = sort.getOrderFor("publishedAt");
            sort = Sort.by(order.getDirection(), "publishedDate");
            pageable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
        }

        Query query = new Query(criteria);
        long total = mongoTemplate.count(query, CrawledArticle.class, "crawled_articles");
        query.with(pageable);
        List<CrawledArticle> items = mongoTemplate.find(query, CrawledArticle.class, "crawled_articles");

        List<ExternalDataItemResponse> responses = items.stream().map(this::crawledToResponse).collect(Collectors.toList());
        return new PageImpl<>(responses, pageable, total);
    }

    public ExternalDataItemResponse getExternalDataById(String id) {
        CrawledArticle article = mongoTemplate.findById(id, CrawledArticle.class, "crawled_articles");
        if (article != null) {
            return crawledToResponse(article);
        }
        return null;
    }

    public String simulateFetch() {
        ExternalDataItem demoNews = ExternalDataItem.builder()
                .title("Tech Corp announces new strategic AI partnership")
                .summary("Tech Corp is expanding its operations into the AI space with a new partnership.")
                .source("TechCrunch Demo")
                .url("https://example.com/demo-news")
                .imageUrl("https://example.com/images/demo.jpg")
                .publishedAt(LocalDateTime.now())
                .category(ExternalDataCategory.NEWS)
                .build();

        ExternalDataItem demoRisk = ExternalDataItem.builder()
                .title("Regulatory investigation opens into Global Finance Inc")
                .summary("Regulators are probing Global Finance Inc over alleged compliance failures.")
                .source("Financial Times Demo")
                .url("https://example.com/demo-risk")
                .publishedAt(LocalDateTime.now())
                .category(ExternalDataCategory.NEWS) // To be analyzed
                .build();

        externalDataRepository.save(demoNews);
        externalDataRepository.save(demoRisk);

        Long currentUserId = getCurrentUserId();
        if (currentUserId != null) {
            auditLogService.log(currentUserId, AuditAction.EXTERNAL_DATA_FETCHED, "ExternalData", "ALL", "Simulated fetching external data");
        }

        return "External data fetch simulated for MVP using stored/demo data.";
    }

    public String simulateAnalyze() {
        List<ExternalDataItem> allNews = externalDataRepository.findAll();

        for (ExternalDataItem item : allNews) {
            boolean updated = false;
            String text = (item.getTitle() + " " + item.getSummary()).toLowerCase();

            if (text.contains("investigation") || text.contains("risk") || text.contains("failure") || text.contains("probing")) {
                item.setCategory(ExternalDataCategory.RISK);
                item.setRiskLevel("HIGH");
                updated = true;
            } else if (text.contains("partnership") || text.contains("growth") || text.contains("opportunity") || text.contains("expanding")) {
                item.setCategory(ExternalDataCategory.OPPORTUNITY);
                item.setOpportunityLevel("HIGH");
                updated = true;
            }

            if (updated) {
                externalDataRepository.save(item);
            }
        }

        Long currentUserId = getCurrentUserId();
        if (currentUserId != null) {
            auditLogService.log(currentUserId, AuditAction.EXTERNAL_DATA_ANALYZED, "ExternalData", "ALL", "Simulated analysis on external data");
        }

        return "External data analysis simulated for MVP using stored/demo data.";
    }

    private ExternalDataItemResponse toResponse(ExternalDataItem item) {
        return ExternalDataItemResponse.builder()
                .id(item.getId())
                .title(item.getTitle())
                .summary(item.getSummary())
                .source(item.getSource())
                .url(item.getUrl())
                .publishedAt(item.getPublishedAt())
                .category(item.getCategory())
                .sentiment(item.getSentiment())
                .riskLevel(item.getRiskLevel())
                .opportunityLevel(item.getOpportunityLevel())
                .relatedCompanyName(item.getRelatedCompanyName())
                .relatedCompanyId(item.getRelatedCompanyId())
                .createdAt(item.getCreatedAt())
                .updatedAt(item.getUpdatedAt())
                .imageUrl(item.getImageUrl())
                .build();
    }

    private ExternalDataItemResponse crawledToResponse(CrawledArticle item) {
        LocalDateTime pubDate = null;
        try {
            if (item.getPublishedDate() != null) {
                if (item.getPublishedDate().contains("T")) {
                    pubDate = LocalDateTime.parse(item.getPublishedDate());
                } else {
                    pubDate = LocalDate.parse(item.getPublishedDate()).atStartOfDay();
                }
            }
        } catch (Exception e) {
            // ignore
        }

        String companyName = null;
        if (item.getMatchedCompanies() != null && !item.getMatchedCompanies().isEmpty()) {
            companyName = item.getMatchedCompanies().get(0).getCompanyName();
        }

        return ExternalDataItemResponse.builder()
                .id(item.getId())
                .title(item.getTitle())
                .summary(item.getSummary())
                .aiSummary(item.getAiSummary())
                .content(item.getContent())
                .source(item.getSourceName())
                .url(item.getUrl())
                .publishedAt(pubDate)
                .category(ExternalDataCategory.NEWS)
                .sentiment(item.getSentiment())
                .riskLevel(item.getPriorityLevel())
                .relatedCompanyName(companyName)
                .imageUrl(item.getThumbnail())
                .createdAt(item.getCrawledAt())
                .build();
    }

    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserDetailsImpl) {
            return ((UserDetailsImpl) auth.getPrincipal()).getId();
        }
        return null;
    }
}
