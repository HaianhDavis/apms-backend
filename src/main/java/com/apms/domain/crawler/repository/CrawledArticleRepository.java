package com.apms.domain.crawler.repository;

import com.apms.domain.crawler.domain.CrawledArticle;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CrawledArticleRepository extends MongoRepository<CrawledArticle, String> {

    List<CrawledArticle> findByCrawledAtAfter(java.time.LocalDateTime date);

    /**
     * Find article by URL for deduplication â€” avoid re-crawling the same article.
     */
    Optional<CrawledArticle> findByUrl(String url);

    Optional<CrawledArticle> findTopByOrderByCrawledAtDesc();

    Page<CrawledArticle> findByAiProcessingStatusOrderByPriorityScoreDescCrawledAtDesc(
            String aiProcessingStatus,
            Pageable pageable);

    /**
     * Check if an article with this URL already exists.
     */
    boolean existsByUrl(String url);

    /**
     * Find all articles pending AI company detection.
     */
    List<CrawledArticle> findByAiProcessingStatus(String aiProcessingStatus);

    List<CrawledArticle> findByAiProcessingStatusInAndAiSummaryIsNull(List<String> aiProcessingStatuses);

    /**
     * Find articles by AI company detection status with pagination.
     */
    Page<CrawledArticle> findByAiProcessingStatus(String aiProcessingStatus, Pageable pageable);

    Page<CrawledArticle> findByMatchedCompaniesCompanyNameIgnoreCase(String companyName, Pageable pageable);

    Page<CrawledArticle> findByAiProcessingStatusAndMatchedCompaniesCompanyNameIgnoreCase(
            String aiProcessingStatus,
            String companyName,
            Pageable pageable);

    Page<CrawledArticle> findBySourceNameIgnoreCase(String sourceName, Pageable pageable);

    Page<CrawledArticle> findByAiProcessingStatusAndSourceNameIgnoreCase(
            String aiProcessingStatus,
            String sourceName,
            Pageable pageable);

    Page<CrawledArticle> findByMatchedCompaniesCompanyNameIgnoreCaseAndSourceNameIgnoreCase(
            String companyName,
            String sourceName,
            Pageable pageable);

    Page<CrawledArticle> findByAiProcessingStatusAndMatchedCompaniesCompanyNameIgnoreCaseAndSourceNameIgnoreCase(
            String aiProcessingStatus,
            String companyName,
            String sourceName,
            Pageable pageable);

    /**
     * Find all matched articles that haven't been published yet.
     */
    List<CrawledArticle> findByAiProcessingStatusAndPublishedAtIsNull(String aiProcessingStatus);

    /**
     * Count articles by processing status.
     */
    long countByAiProcessingStatus(String aiProcessingStatus);
}

