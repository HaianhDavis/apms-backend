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
import java.util.Map;
import java.util.Set;
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
    private final com.apms.common.security.StaffCompanyScopeEvaluator companyScope;
    private final com.apms.domain.profile.repository.mongo.CompanyProfileRepository companyProfileRepository;

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

        List<com.apms.domain.crawler.domain.TrackedCompany> activeCompanies = trackedCompanyRepository.findByIsActiveTrue();

        List<com.apms.domain.profile.CompanyProfile> allProfiles = companyProfileRepository.findAll();
        Map<String, com.apms.domain.profile.CompanyProfile> profileById = allProfiles.stream()
                .filter(p -> p.getId() != null)
                .collect(Collectors.toMap(com.apms.domain.profile.CompanyProfile::getId, p -> p, (a, b) -> a));

        // Exclude deleted or hidden profiles from user-facing news
        activeCompanies = activeCompanies.stream()
                .filter(c -> {
                    com.apms.domain.profile.CompanyProfile p = profileById.get(c.getId());
                    if (p != null) {
                        return !Boolean.TRUE.equals(p.getIsDeleted()) && !Boolean.TRUE.equals(p.getIsHidden());
                    }
                    return true;
                })
                .collect(Collectors.toList());

        // Enforce staff company scope if caller is STAFF
        if (companyScope != null && companyScope.isCurrentUserStaff()) {
            Set<String> allowedIds = companyScope.allowedCompanyIds();
            if (allowedIds != null) {
                activeCompanies = activeCompanies.stream()
                        .filter(c -> {
                            if (allowedIds.contains(c.getId())) return true;
                            com.apms.domain.profile.CompanyProfile p = profileById.get(c.getId());
                            return p != null && p.getCompanyId() != null && allowedIds.contains(p.getCompanyId());
                        })
                        .collect(Collectors.toList());
            }
        }

        List<String> validIds = activeCompanies.stream()
                .map(com.apms.domain.crawler.domain.TrackedCompany::getId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());
        List<String> validNames = activeCompanies.stream()
                .map(com.apms.domain.crawler.domain.TrackedCompany::getCompanyName)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());

        if (StringUtils.hasText(companyName)) {
            String queryCompany = companyName.trim();
            com.apms.domain.profile.CompanyProfile targetProfile = companyProfileRepository.findById(queryCompany)
                    .or(() -> companyProfileRepository.findByCompanyId(queryCompany))
                    .or(() -> companyProfileRepository.findAll().stream()
                            .filter(p -> p.getIdentity() != null && (
                                    queryCompany.equalsIgnoreCase(p.getIdentity().getTradeName()) ||
                                    queryCompany.equalsIgnoreCase(p.getIdentity().getLegalName())
                            ))
                            .findFirst())
                    .orElse(null);

            com.apms.domain.crawler.domain.TrackedCompany targetTc = null;
            if (targetProfile != null) {
                targetTc = trackedCompanyRepository.findById(targetProfile.getId()).orElse(null);
            }
            if (targetTc == null) {
                targetTc = trackedCompanyRepository.findById(queryCompany)
                        .or(() -> trackedCompanyRepository.findByCompanyNameIgnoreCase(queryCompany))
                        .orElse(null);
                if (targetTc != null && targetProfile == null) {
                    targetProfile = companyProfileRepository.findById(targetTc.getId()).orElse(null);
                }
            }

            // Check if caller is authorized to view this company
            if (companyScope != null && companyScope.isCurrentUserStaff()) {
                String targetId = targetProfile != null ? targetProfile.getId() : (targetTc != null ? targetTc.getId() : queryCompany);
                boolean authorized = activeCompanies.stream().anyMatch(c -> c.getId().equals(targetId));
                if (!authorized) {
                    return Page.empty(pageable);
                }
            }

            List<Criteria> orCriterias = new ArrayList<>();
            java.util.Set<String> searchStrings = new java.util.HashSet<>();
            searchStrings.add(queryCompany);

            if (targetProfile != null) {
                if (targetProfile.getId() != null) {
                    orCriterias.add(Criteria.where("matchedCompanies.companyId").is(targetProfile.getId()));
                }
                if (targetProfile.getCompanyId() != null) {
                    orCriterias.add(Criteria.where("matchedCompanies.companyId").is(targetProfile.getCompanyId()));
                }
                if (targetProfile.getIdentity() != null) {
                    if (StringUtils.hasText(targetProfile.getIdentity().getTradeName())) {
                        searchStrings.add(targetProfile.getIdentity().getTradeName().trim());
                    }
                    if (StringUtils.hasText(targetProfile.getIdentity().getLegalName())) {
                        searchStrings.add(targetProfile.getIdentity().getLegalName().trim());
                    }
                }
            }
            if (targetTc != null) {
                if (targetTc.getId() != null) {
                    orCriterias.add(Criteria.where("matchedCompanies.companyId").is(targetTc.getId()));
                }
                if (StringUtils.hasText(targetTc.getCompanyName())) {
                    searchStrings.add(targetTc.getCompanyName().trim());
                }
                if (StringUtils.hasText(targetTc.getDisplayName())) {
                    searchStrings.add(targetTc.getDisplayName().trim());
                }
                if (targetTc.getAliases() != null) {
                    for (String alias : targetTc.getAliases()) {
                        if (StringUtils.hasText(alias)) {
                            searchStrings.add(alias.trim());
                        }
                    }
                }
            }

            for (String str : searchStrings) {
                orCriterias.add(Criteria.where("matchedCompanies.companyName").regex("^" + java.util.regex.Pattern.quote(str) + "$", "i"));
                orCriterias.add(Criteria.where("matchedCompanies.companyName").regex(java.util.regex.Pattern.quote(str), "i"));
            }

            criteria.andOperator(new Criteria().orOperator(orCriterias.toArray(new Criteria[0])));
        } else if (!activeCompanies.isEmpty()) {
            List<Criteria> canonicalOr = new ArrayList<>();
            if (!validIds.isEmpty()) {
                canonicalOr.add(Criteria.where("matchedCompanies.companyId").in(validIds));
            }
            if (!validNames.isEmpty()) {
                canonicalOr.add(Criteria.where("matchedCompanies.companyName").in(validNames));
            }
            if (!canonicalOr.isEmpty()) {
                criteria.andOperator(new Criteria().orOperator(canonicalOr.toArray(new Criteria[0])));
            }
        } else {
            return Page.empty(pageable);
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
        String companyId = null;
        if (item.getMatchedCompanies() != null && !item.getMatchedCompanies().isEmpty()) {
            List<com.apms.domain.crawler.domain.TrackedCompany> activeTc = trackedCompanyRepository.findByIsActiveTrue();
            java.util.Set<String> activeNames = new java.util.HashSet<>();
            java.util.Set<String> activeIds = new java.util.HashSet<>();

            for (com.apms.domain.crawler.domain.TrackedCompany tc : activeTc) {
                if (tc.getId() != null) activeIds.add(tc.getId());
                if (tc.getCompanyName() != null) activeNames.add(tc.getCompanyName().toLowerCase());
                if (tc.getDisplayName() != null) activeNames.add(tc.getDisplayName().toLowerCase());
                if (tc.getAliases() != null) {
                    for (String a : tc.getAliases()) {
                        if (a != null) activeNames.add(a.toLowerCase());
                    }
                }
            }

            // Prefer canonical APMS company match over incidental mentions
            com.apms.domain.crawler.domain.CompanyMatch canonicalMatch = item.getMatchedCompanies().stream()
                    .filter(m -> (m.getCompanyId() != null && activeIds.contains(m.getCompanyId()))
                              || (m.getCompanyName() != null && activeNames.contains(m.getCompanyName().toLowerCase())))
                    .findFirst()
                    .orElse(item.getMatchedCompanies().get(0));

            companyId = canonicalMatch.getCompanyId();
            String rawMatchName = canonicalMatch.getCompanyName();

            // Resolve canonical CompanyProfile to enforce canonical UI display name
            com.apms.domain.profile.CompanyProfile canonicalProfile = null;
            if (companyId != null) {
                final String targetCompanyId = companyId;
                canonicalProfile = companyProfileRepository.findById(targetCompanyId)
                        .or(() -> companyProfileRepository.findByCompanyId(targetCompanyId))
                        .orElse(null);
            }
            if (canonicalProfile == null && rawMatchName != null) {
                String matchLower = rawMatchName.toLowerCase();
                com.apms.domain.crawler.domain.TrackedCompany matchedTc = activeTc.stream()
                        .filter(tc -> (tc.getCompanyName() != null && tc.getCompanyName().equalsIgnoreCase(matchLower))
                                   || (tc.getDisplayName() != null && tc.getDisplayName().equalsIgnoreCase(matchLower))
                                   || (tc.getAliases() != null && tc.getAliases().stream().anyMatch(a -> a.equalsIgnoreCase(matchLower))))
                        .findFirst()
                        .orElse(null);
                if (matchedTc != null && matchedTc.getId() != null) {
                    canonicalProfile = companyProfileRepository.findById(matchedTc.getId()).orElse(null);
                }
                if (canonicalProfile == null) {
                    canonicalProfile = companyProfileRepository.searchByName("^" + java.util.regex.Pattern.quote(rawMatchName) + "$", org.springframework.data.domain.PageRequest.of(0, 1))
                            .stream().findFirst().orElse(null);
                }
            }

            if (canonicalProfile != null) {
                companyId = canonicalProfile.getId();
                companyName = canonicalProfile.resolveDisplayName();
            } else {
                companyName = rawMatchName;
            }
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
                .relatedCompanyId(companyId)
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
