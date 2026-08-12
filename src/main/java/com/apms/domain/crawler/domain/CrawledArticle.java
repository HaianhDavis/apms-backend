package com.apms.domain.crawler.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * MongoDB document representing a crawled news article.
 * Stored in its own collection before AI filtering decides whether
 * to publish it to the raw_documents collection used by the backend.
 *
 * Collection: crawled_articles
 */
@Document(collection = "crawled_articles")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CrawledArticle {

    @Id
    private String id;

    /** Article headline */
    private String title;

    /** Short description or snippet from RSS feed */
    private String summary;

    /** AI-generated Vietnamese summary for feed display */
    private String aiSummary;

    /** Full article text content (extracted from article URL) */
    private String content;

    /** Direct URL to the article page */
    @Indexed(unique = true)
    private String url;

    /** RSS feed URL this article came from */
    private String sourceUrl;

    /** Human-readable source name (e.g., "CafeF", "TechCrunch") */
    private String sourceName;

    /** Thumbnail image URL */
    private String thumbnail;

    /** ISO date string for publication date, e.g., "2026-07-15" */
    private String publishedDate;

    /** Language of the article: "vi", "en" */
    private String language;

    /** Article sentiment for owner triage: POSITIVE, NEGATIVE, NEUTRAL */
    @Indexed
    private String sentiment;

    /** Information type: FINANCIAL, TECHNOLOGY, LEGAL, PRODUCT, MARKET, LEADERSHIP, PARTNERSHIP, INCIDENT, GENERAL */
    @Indexed
    private String informationType;

    /** Priority level for continuous crawler updates: HIGH, MEDIUM, LOW */
    @Indexed
    private String priorityLevel;

    /** Numeric priority score used to order hot/important articles */
    @Indexed
    private Integer priorityScore;

    /** Short reason explaining why the article has this priority */
    private String priorityReason;

    // ─────────────────────────────────────────────────────────────
    // AI Company Detection Results
    // ─────────────────────────────────────────────────────────────

    /**
     * List of tracked companies matched to this article.
     * Populated after AI detection pipeline runs.
     */
    @Builder.Default
    private List<CompanyMatch> matchedCompanies = new ArrayList<>();

    /**
     * AI processing lifecycle status:
     * PENDING    — crawled but not yet analyzed
     * MATCHED    — AI found relevant tracked companies
     * DISCARDED  — AI determined no relevance to any tracked company
     * PUBLISHED  — article pushed to raw_documents for backend consumption
     * ERROR      — AI processing failed
     */
    @Indexed
    @Builder.Default
    private String aiProcessingStatus = "PENDING";

    /** Raw AI response JSON (for debugging/audit) */
    private String rawAiOutput;

    /** Time taken for AI processing in milliseconds */
    private Long aiProcessingTimeMs;

    /** Error message if AI processing failed */
    private String aiErrorMessage;

    // ─────────────────────────────────────────────────────────────
    // Timestamps
    // ─────────────────────────────────────────────────────────────

    @CreatedDate
    private LocalDateTime crawledAt;

    /** When AI company detection was completed */
    private LocalDateTime processedAt;

    /** When the article was published to raw_documents */
    private LocalDateTime publishedAt;
}
