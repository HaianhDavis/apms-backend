package com.apms.domain.listing.controller;

import com.apms.common.response.ApiResponse;
import com.apms.domain.listing.dto.CompanyNewsSearchResponse;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.repository.mongo.CompanyProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import com.apms.domain.crawler.domain.CrawledArticle;
import com.apms.domain.crawler.repository.CrawledArticleRepository;
import com.apms.domain.listing.dto.CompanyNews;
import com.apms.domain.listing.dto.ListingTabResponse;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/companies")
@RequiredArgsConstructor
@Slf4j
public class ListingDataController {

    private final CompanyProfileRepository profileRepository;
    private final CrawledArticleRepository crawledArticleRepository;
    private final MongoTemplate mongoTemplate;

    @PostMapping("/{companyId}/search-news")
    public ResponseEntity<ApiResponse<CompanyNewsSearchResponse>> searchCompanyNews(@PathVariable String companyId) {
        log.info("Received request to search news for companyId: {}", companyId);

        String companyName = "Unknown Company";
        var profileOpt = profileRepository.findByCompanyId(companyId);
        if (profileOpt.isEmpty()) {
            profileOpt = profileRepository.findById(companyId);
        }

        if (profileOpt.isPresent()) {
            CompanyProfile profile = profileOpt.get();
            if (profile.getIdentity() != null && profile.getIdentity().getLegalName() != null) {
                companyName = profile.getIdentity().getLegalName();
            } else if (profile.getIdentity() != null && profile.getIdentity().getTradeName() != null) {
                companyName = profile.getIdentity().getTradeName();
            }
        }

        // Return a dummy response for now, as real crawling might take too long synchronously
        CompanyNewsSearchResponse response = CompanyNewsSearchResponse.builder()
                .companyId(companyId)
                .companyName(companyName)
                .searchedAt(LocalDateTime.now())
                .savedNew(0)
                .alreadyExisting(0)
                .rejected(0)
                .results(new ArrayList<>())
                .build();

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/{companyId}/news")
    public ResponseEntity<ApiResponse<ListingTabResponse<List<CompanyNews>>>> getCompanyNews(@PathVariable String companyId) {
        log.info("Received request to get news for companyId: {}", companyId);

        var profileOpt = profileRepository.findByCompanyId(companyId);
        if (profileOpt.isEmpty()) {
            profileOpt = profileRepository.findById(companyId);
        }

        List<String> names = new ArrayList<>();
        if (profileOpt.isPresent()) {
            CompanyProfile profile = profileOpt.get();
            if (profile.getIdentity() != null) {
                if (org.springframework.util.StringUtils.hasText(profile.getIdentity().getLegalName())) {
                    names.add(profile.getIdentity().getLegalName().trim());
                }
                if (org.springframework.util.StringUtils.hasText(profile.getIdentity().getTradeName())) {
                    names.add(profile.getIdentity().getTradeName().trim());
                }
            }
        }

        List<CrawledArticle> articles = new ArrayList<>();
        if (!names.isEmpty()) {
            List<Criteria> orCriterias = new ArrayList<>();
            for (String name : names) {
                orCriterias.add(Criteria.where("matchedCompanies.companyName").regex(name, "i"));
                orCriterias.add(Criteria.where("title").regex(name, "i"));
            }
            Query query = new Query(new Criteria().orOperator(orCriterias.toArray(new Criteria[0])));
            articles = mongoTemplate.find(query, CrawledArticle.class, "crawled_articles");
        }

        List<CompanyNews> newsList = articles.stream().map(article -> {
            LocalDateTime pubDate = null;
            if (article.getPublishedDate() != null) {
                try {
                    if (article.getPublishedDate().contains("T")) {
                        pubDate = LocalDateTime.parse(article.getPublishedDate());
                    } else {
                        pubDate = LocalDate.parse(article.getPublishedDate()).atStartOfDay();
                    }
                } catch (DateTimeParseException e) {
                    log.warn("Failed to parse date for article {}: {}", article.getId(), article.getPublishedDate());
                }
            }
            
            return CompanyNews.builder()
                    .id(article.getId())
                    .newsType(1) 
                    .title(article.getTitle())
                    .summary(article.getSummary())
                    .sourceUrl(article.getUrl() != null ? article.getUrl() : article.getSourceUrl())
                    .imageUrl(article.getThumbnail())
                    .publishedAt(pubDate)
                    .crawledAt(article.getCrawledAt())
                    .build();
        }).collect(Collectors.toList());

        ListingTabResponse<List<CompanyNews>> response = ListingTabResponse.<List<CompanyNews>>builder()
                .hasData(!newsList.isEmpty())
                .crawledAt(LocalDateTime.now())
                .data(newsList)
                .build();

        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
