package com.apms.domain.crawler.crawl;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Extracts full article text content from article URLs.
 * Used to provide the AI with more context beyond the RSS summary.
 */
@Slf4j
@Component
public class ArticleContentExtractor {

    @Value("${crawler.content.fetch-delay-ms:1000}")
    private long fetchDelayMs;

    @Value("${crawler.content.max-length:5000}")
    private int maxContentLength;

    /**
     * Fetch and extract the main text content from an article URL.
     *
     * @param articleUrl the URL of the article
     * @return extracted text content, or null if extraction fails
     */
    public String extractContent(String articleUrl) {
        if (articleUrl == null || articleUrl.isBlank()) {
            return null;
        }

        try {
            // Rate-limit to avoid IP bans
            Thread.sleep(fetchDelayMs);

            Document doc = Jsoup.connect(articleUrl)
                    .userAgent("APMS-Crawler/1.0 (Business Intelligence)")
                    .timeout(10_000)
                    .followRedirects(true)
                    .get();

            String content = extractMainContent(doc);

            if (content != null && content.length() > maxContentLength) {
                content = content.substring(0, maxContentLength) + "...";
            }

            return content;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("ArticleContentExtractor: Interrupted while fetching: {}", articleUrl);
            return null;
        } catch (Exception e) {
            log.warn("ArticleContentExtractor: Failed to extract content from '{}': {}",
                    articleUrl, e.getMessage());
            return null;
        }
    }

    /**
     * Extract the main text content from the parsed HTML document.
     * Tries multiple CSS selectors in order of specificity.
     */
    private String extractMainContent(Document doc) {
        // Try common article content selectors (ordered by specificity)
        String[] selectors = {
                "article .content",
                "article .detail-content",
                "article .article-body",
                ".detail_text_news",       // CafeF
                ".maincontent",            // VietnamNet
                ".article-content",
                ".post-content",
                ".entry-content",
                "article",
                "[itemprop=articleBody]",
                ".story-body",
                "main"
        };

        for (String selector : selectors) {
            Elements elements = doc.select(selector);
            if (!elements.isEmpty()) {
                String text = cleanContent(elements.first());
                if (text != null && text.length() > 100) {
                    return text;
                }
            }
        }

        // Fallback: try meta description
        Element metaDesc = doc.selectFirst("meta[property=og:description]");
        if (metaDesc != null) {
            String content = metaDesc.attr("content");
            if (content != null && !content.isBlank()) {
                return content.trim();
            }
        }

        // Last resort: body text (truncated)
        String bodyText = doc.body() != null ? doc.body().text() : null;
        if (bodyText != null && bodyText.length() > 200) {
            return bodyText;
        }

        return null;
    }

    /**
     * Clean HTML element content: remove scripts, styles, ads, navigation.
     */
    private String cleanContent(Element element) {
        if (element == null) return null;

        // Clone to avoid modifying the original
        Element clone = element.clone();

        // Remove unwanted elements
        clone.select("script, style, nav, footer, header, .ads, .advertisement, " +
                ".social-share, .related-articles, .comments, .sidebar, " +
                "[class*=quangcao], [class*=ads], [id*=ads]").remove();

        String text = clone.text().trim();

        // Remove excessive whitespace
        text = text.replaceAll("\\s{3,}", "\n\n");

        return text.isBlank() ? null : text;
    }
}

