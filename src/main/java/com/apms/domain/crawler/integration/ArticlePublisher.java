package com.apms.domain.crawler.integration;

import com.apms.domain.crawler.domain.CompanyMatch;
import com.apms.domain.crawler.domain.CrawledArticle;
import com.apms.domain.crawler.repository.CrawledArticleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Publishes matched articles to the raw_documents MongoDB collection
 * (the same collection used by apms-backend's RawDocument entity).
 *
 * This bridges the crawler microservice with the backend's existing workflow:
 * raw_documents â†’ view crawl results â†’ select articles â†’ AI extraction.
 *
 * Uses MongoTemplate directly to write to raw_documents without depending
 * on the backend's Java entity classes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArticlePublisher {

    private static final String RAW_DOCUMENTS_COLLECTION = "raw_documents";

    private final MongoTemplate mongoTemplate;
    private final CrawledArticleRepository crawledArticleRepository;

    /**
     * Publish all matched (but not yet published) articles to raw_documents.
     *
     * @return number of articles published
     */
    public int publishMatchedArticles() {
        List<CrawledArticle> matched = crawledArticleRepository
                .findByAiProcessingStatusAndPublishedAtIsNull("MATCHED");

        if (matched.isEmpty()) {
            log.debug("ArticlePublisher: No matched articles to publish.");
            return 0;
        }

        log.info("ArticlePublisher: Publishing {} matched articles to raw_documents", matched.size());

        int published = 0;
        for (CrawledArticle article : matched) {
            try {
                // Check if already exists in raw_documents (by original URL)
                if (existsInRawDocuments(article.getUrl())) {
                    log.debug("ArticlePublisher: Skipping duplicate (already in raw_documents): {}",
                            article.getUrl());
                    article.setAiProcessingStatus("PUBLISHED");
                    article.setPublishedAt(LocalDateTime.now());
                    crawledArticleRepository.save(article);
                    continue;
                }

                // Build the raw_documents document matching the backend's RawDocument schema
                Map<String, Object> rawDoc = buildRawDocument(article);
                mongoTemplate.insert(rawDoc, RAW_DOCUMENTS_COLLECTION);

                // Mark as published
                article.setAiProcessingStatus("PUBLISHED");
                article.setPublishedAt(LocalDateTime.now());
                crawledArticleRepository.save(article);

                published++;
                log.debug("ArticlePublisher: Published article: {}", article.getTitle());

            } catch (Exception e) {
                log.error("ArticlePublisher: Failed to publish article '{}': {}",
                        article.getTitle(), e.getMessage(), e);
            }
        }

        log.info("ArticlePublisher: Published {} articles to raw_documents", published);
        return published;
    }

    /**
     * Build a Map matching the backend's RawDocument MongoDB schema.
     * Fields mirror RawDocument.java from apms-backend.
     */
    private Map<String, Object> buildRawDocument(CrawledArticle article) {
        LocalDateTime now = LocalDateTime.now();

        // Build matched company names for companyNameHint
        String companyNameHint = article.getMatchedCompanies().stream()
                .map(CompanyMatch::getCompanyName)
                .collect(Collectors.joining(", "));

        Map<String, Object> doc = new HashMap<>();

        // Article fields (top-level, matching RawDocument schema)
        doc.put("title", article.getTitle());
        doc.put("summary", article.getAiSummary() != null ? article.getAiSummary() : article.getSummary());
        doc.put("publishedDate", article.getPublishedDate());
        doc.put("thumbnail", article.getThumbnail());
        doc.put("status", "CRAWLED");
        doc.put("isHidden", false);

        // Source sub-document
        Map<String, Object> source = new HashMap<>();
        source.put("type", "WEBSITE");
        source.put("originalUrl", article.getUrl());
        source.put("inputText", article.getContent() != null ? article.getContent() : article.getSummary());
        source.put("companyNameHint", companyNameHint);
        doc.put("source", source);

        // Storage sub-document
        Map<String, Object> storage = new HashMap<>();
        storage.put("provider", "WEBSITE");
        doc.put("storage", storage);

        // Processing sub-document
        Map<String, Object> processing = new HashMap<>();
        processing.put("status", "UPLOADED");
        processing.put("candidateCount", 0);
        processing.put("startedAt", now);
        doc.put("processing", processing);

        // Metadata sub-document
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("uploadedBy", "CRAWLER");
        metadata.put("uploadedAt", now);
        metadata.put("updatedAt", now);
        doc.put("metadata", metadata);

        // Crawler-specific fields (additional context for the backend)
        doc.put("crawlerSource", article.getSourceName());
        doc.put("crawlerLanguage", article.getLanguage());
        doc.put("matchedCompanies", article.getMatchedCompanies());
        doc.put("crawledArticleId", article.getId());

        return doc;
    }

    /**
     * Check if an article with this URL already exists in raw_documents.
     */
    private boolean existsInRawDocuments(String url) {
        Query query = new Query(Criteria.where("source.originalUrl").is(url));
        return mongoTemplate.exists(query, RAW_DOCUMENTS_COLLECTION);
    }
}

