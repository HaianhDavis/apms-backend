package com.apms.domain.crawler.ai;

import com.apms.domain.crawler.crawl.ArticleContentExtractor;
import com.apms.domain.crawler.domain.CompanyMatch;
import com.apms.domain.crawler.domain.CrawledArticle;
import com.apms.domain.crawler.domain.TrackedCompany;
import com.apms.domain.crawler.repository.CrawledArticleRepository;
import com.apms.domain.crawler.service.TrackedCompanyCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Orchestrates the full AI company matching pipeline.
 *
 * Pipeline per article:
 * 1. Load tracked companies (from in-memory cache)
 * 2. Fast pre-filter: exact alias/keyword matching on title + summary
 * 3. If no pre-filter match → call Gemini AI for semantic matching
 * 4. Update article with results (MATCHED or DISCARDED)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompanyDetectionService {

    private final TrackedCompanyCache companyCache;
    private final GeminiCompanyDetector geminiDetector;
    private final ArticleContentExtractor contentExtractor;
    private final CrawledArticleRepository articleRepository;

    /**
     * Process all articles with PENDING AI status.
     *
     * @return number of articles that matched at least one tracked company
     */
    public int processAllPending() {
        List<CrawledArticle> pendingArticles = articleRepository.findByAiProcessingStatus("PENDING");

        if (pendingArticles.isEmpty()) {
            log.debug("CompanyDetectionService: No pending articles to process.");
            return 0;
        }

        List<TrackedCompany> trackedCompanies = companyCache.getActiveCompanies();
        if (trackedCompanies.isEmpty()) {
            log.warn("CompanyDetectionService: No tracked companies configured. Skipping detection.");
            return 0;
        }

        log.info("CompanyDetectionService: Processing {} pending articles against {} tracked companies",
                pendingArticles.size(), trackedCompanies.size());

        int matchedCount = 0;

        for (CrawledArticle article : pendingArticles) {
            try {
                boolean matched = processArticle(article, trackedCompanies);
                if (matched) matchedCount++;
            } catch (Exception e) {
                log.error("CompanyDetectionService: Error processing article '{}': {}",
                        article.getTitle(), e.getMessage(), e);
                article.setAiProcessingStatus("ERROR");
                article.setAiErrorMessage(e.getMessage());
                article.setProcessedAt(LocalDateTime.now());
                articleRepository.save(article);
            }
        }

        log.info("CompanyDetectionService: Processed {} articles, {} matched, {} discarded/error",
                pendingArticles.size(), matchedCount, pendingArticles.size() - matchedCount);

        return matchedCount;
    }

    /**
     * Process a single article through the detection pipeline.
     *
     * @return true if the article matched at least one tracked company
     */
    private boolean processArticle(CrawledArticle article, List<TrackedCompany> trackedCompanies) {
        // Step 1: Try fast pre-filter (alias/keyword matching)
        CompanyDetectionResult preFilterResult = fastPreFilter(article, trackedCompanies);

        if (preFilterResult.isRelevant()) {
            // Pre-filter found matches — no need for AI call
            article.setMatchedCompanies(preFilterResult.getMatches());
            article.setAiProcessingStatus("MATCHED");
            article.setRawAiOutput("PRE-FILTER: " + preFilterResult.getMatches().size() + " match(es)");
            article.setAiProcessingTimeMs(preFilterResult.getProcessingTimeMs());
            article.setProcessedAt(LocalDateTime.now());
            articleRepository.save(article);

            log.debug("CompanyDetectionService: PRE-FILTER match for '{}': {}",
                    article.getTitle(),
                    preFilterResult.getMatches().stream()
                            .map(CompanyMatch::getCompanyName)
                            .toList());
            return true;
        }

        // Step 2: Fetch full article content for better AI analysis
        String fullContent = buildFullContent(article);

        // Step 3: Call Gemini AI for semantic matching
        CompanyDetectionResult aiResult = geminiDetector.detect(
                article.getTitle(), fullContent, trackedCompanies);

        // Step 4: Update article based on AI result
        if (aiResult.getErrorMessage() != null) {
            article.setAiProcessingStatus("ERROR");
            article.setAiErrorMessage(aiResult.getErrorMessage());
        } else if (aiResult.isRelevant() && !aiResult.getMatches().isEmpty()) {
            article.setMatchedCompanies(aiResult.getMatches());
            article.setAiProcessingStatus("MATCHED");
        } else {
            article.setAiProcessingStatus("DISCARDED");
        }

        article.setRawAiOutput(aiResult.getRawAiOutput());
        article.setAiProcessingTimeMs(aiResult.getProcessingTimeMs());
        article.setProcessedAt(LocalDateTime.now());
        articleRepository.save(article);

        if ("MATCHED".equals(article.getAiProcessingStatus())) {
            log.debug("CompanyDetectionService: AI MATCH for '{}': {}",
                    article.getTitle(),
                    aiResult.getMatches().stream()
                            .map(m -> m.getCompanyName() + "(" + m.getConfidenceScore() + ")")
                            .toList());
            return true;
        }

        return false;
    }

    /**
     * Fast pre-filter: check if any tracked company name, alias, subsidiary,
     * product, or key person appears literally in the article title or summary.
     * This avoids expensive AI calls for obvious matches.
     */
    private CompanyDetectionResult fastPreFilter(CrawledArticle article,
                                                  List<TrackedCompany> trackedCompanies) {
        long startTime = System.currentTimeMillis();

        String searchText = ((article.getTitle() != null ? article.getTitle() : "") + " " +
                (article.getSummary() != null ? article.getSummary() : "")).toLowerCase();

        if (searchText.isBlank()) {
            return CompanyDetectionResult.builder()
                    .relevant(false)
                    .processingTimeMs(System.currentTimeMillis() - startTime)
                    .fromPreFilter(true)
                    .build();
        }

        List<CompanyMatch> matches = new ArrayList<>();

        for (TrackedCompany company : trackedCompanies) {
            // Build list of all keywords to check
            List<String> keywords = new ArrayList<>();
            keywords.add(company.getCompanyName());

            if (company.getAliases() != null) keywords.addAll(company.getAliases());
            if (company.getSubsidiaries() != null) keywords.addAll(company.getSubsidiaries());
            if (company.getProducts() != null) keywords.addAll(company.getProducts());
            if (company.getKeyPeople() != null) keywords.addAll(company.getKeyPeople());

            // Check each keyword against the article text
            for (String keyword : keywords) {
                if (keyword == null || keyword.isBlank() || keyword.length() < 2) continue;

                if (searchText.contains(keyword.toLowerCase())) {
                    // Determine match type and confidence
                    String matchType;
                    double confidence;
                    if (company.getCompanyName().equalsIgnoreCase(keyword)) {
                        matchType = "EXACT";
                        confidence = 1.0;
                    } else {
                        matchType = "ALIAS";
                        confidence = 0.85;
                    }

                    // Avoid duplicate company matches
                    boolean alreadyMatched = matches.stream()
                            .anyMatch(m -> m.getCompanyId() != null &&
                                    m.getCompanyId().equals(company.getId()));

                    if (!alreadyMatched) {
                        matches.add(CompanyMatch.builder()
                                .companyId(company.getId())
                                .companyName(company.getCompanyName())
                                .confidenceScore(confidence)
                                .matchReason("Keyword '" + keyword + "' found in article title/summary")
                                .matchType(matchType)
                                .build());
                    }
                    break; // One match per company is enough
                }
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        return CompanyDetectionResult.builder()
                .matches(matches)
                .relevant(!matches.isEmpty())
                .processingTimeMs(elapsed)
                .fromPreFilter(true)
                .build();
    }

    /**
     * Build the full content string for AI analysis.
     * Combines RSS summary with fetched article body.
     */
    private String buildFullContent(CrawledArticle article) {
        StringBuilder sb = new StringBuilder();

        if (article.getSummary() != null) {
            sb.append(article.getSummary());
        }

        // Try to fetch full article content if not already present
        if (article.getContent() == null || article.getContent().isBlank()) {
            String extracted = contentExtractor.extractContent(article.getUrl());
            if (extracted != null && !extracted.isBlank()) {
                article.setContent(extracted);
                sb.append("\n\n").append(extracted);
            }
        } else {
            sb.append("\n\n").append(article.getContent());
        }

        return sb.toString();
    }
}
