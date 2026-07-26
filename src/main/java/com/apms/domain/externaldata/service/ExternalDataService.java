package com.apms.domain.externaldata.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.ExternalDataCategory;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.externaldata.ExternalDataItem;
import com.apms.domain.externaldata.dto.ExternalDataItemResponse;
import com.apms.domain.externaldata.repository.mongo.ExternalDataRepository;
import com.apms.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
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

    public Page<ExternalDataItemResponse> getExternalData(
            ExternalDataCategory category,
            String keyword,
            String source,
            LocalDateTime fromDate,
            LocalDateTime toDate,
            Pageable pageable) {

        Criteria criteria = new Criteria();

        if (category != null) {
            criteria.and("category").is(category);
        }

        if (StringUtils.hasText(source)) {
            criteria.and("source").is(source);
        }

        if (StringUtils.hasText(keyword)) {
            criteria.orOperator(
                    Criteria.where("title").regex(keyword, "i"),
                    Criteria.where("summary").regex(keyword, "i")
            );
        }

        if (fromDate != null) {
            criteria.and("publishedAt").gte(fromDate);
        }
        if (toDate != null) {
            criteria.andOperator(Criteria.where("publishedAt").lte(toDate));
        }

        Query query = new Query(criteria);
        long total = mongoTemplate.count(query, ExternalDataItem.class);
        query.with(pageable);
        List<ExternalDataItem> items = mongoTemplate.find(query, ExternalDataItem.class);

        List<ExternalDataItemResponse> responses = items.stream().map(this::toResponse).collect(Collectors.toList());
        return new PageImpl<>(responses, pageable, total);
    }

    public String simulateFetch() {
        ExternalDataItem demoNews = ExternalDataItem.builder()
                .title("Tech Corp announces new strategic AI partnership")
                .summary("Tech Corp is expanding its operations into the AI space with a new partnership.")
                .source("TechCrunch Demo")
                .url("https://example.com/demo-news")
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
