package com.apms.domain.crawler.crawl;

import com.apms.domain.crawler.domain.CrawledArticle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Fetches and parses RSS XML feeds into CrawledArticle objects.
 * Reuses the proven XML/HTML parsing logic from the backend's RssCrawlerProvider.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RssFeedCrawler {

    private final RestClient restClient;

    /**
     * Crawl articles from a single RSS feed source.
     *
     * @param source the RSS feed configuration
     * @return list of parsed articles (may be empty on failure)
     */
    public List<CrawledArticle> crawl(RssFeedSource source) {
        log.info("RssFeedCrawler: Fetching from '{}' ({})", source.getName(), source.getUrl());

        try {
            String xmlContent = restClient.get()
                    .uri(source.getUrl())
                    .retrieve()
                    .body(String.class);

            if (xmlContent == null || xmlContent.isBlank()) {
                log.warn("RssFeedCrawler: Empty response from '{}'", source.getName());
                return List.of();
            }

            List<CrawledArticle> articles = parseRssXml(xmlContent, source);
            log.info("RssFeedCrawler: Parsed {} articles from '{}'", articles.size(), source.getName());
            return articles;

        } catch (Exception e) {
            log.error("RssFeedCrawler: Failed to fetch from '{}': {}", source.getName(), e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Parse RSS XML content into CrawledArticle objects.
     */
    private List<CrawledArticle> parseRssXml(String xmlContent, RssFeedSource source) {
        List<CrawledArticle> articles = new ArrayList<>();

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // Security: disable external entities to prevent XXE attacks
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(xmlContent)));
            doc.getDocumentElement().normalize();

            NodeList items = doc.getElementsByTagName("item");
            int count = Math.min(items.getLength(), source.getMaxArticles());

            for (int i = 0; i < count; i++) {
                try {
                    org.w3c.dom.Element item = (org.w3c.dom.Element) items.item(i);
                    CrawledArticle article = parseItem(item, source);
                    if (article != null) {
                        articles.add(article);
                    }
                } catch (Exception e) {
                    log.warn("RssFeedCrawler: Failed to parse item {} from '{}': {}",
                            i, source.getName(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("RssFeedCrawler: Failed to parse XML from '{}': {}", source.getName(), e.getMessage(), e);
        }

        return articles;
    }

    /**
     * Parse a single RSS <item> element into a CrawledArticle.
     */
    private CrawledArticle parseItem(org.w3c.dom.Element item, RssFeedSource source) {
        String title = getElementText(item, "title");
        String link = getElementText(item, "link");
        String description = getElementText(item, "description");
        String pubDateStr = getElementText(item, "pubDate");

        if (title == null || title.isBlank() || link == null || link.isBlank()) {
            return null;
        }

        title = title.trim();
        link = link.trim();

        // Extract thumbnail and summary from HTML description using Jsoup
        String thumbnail = null;
        String summary = null;

        if (description != null && !description.isBlank()) {
            try {
                org.jsoup.nodes.Document htmlDoc = Jsoup.parse(description);

                // Extract thumbnail from <img> tag
                Element img = htmlDoc.selectFirst("img");
                if (img != null) {
                    thumbnail = img.attr("src");
                    if (thumbnail != null && thumbnail.isBlank()) {
                        thumbnail = null;
                    }
                }

                // Extract summary text
                summary = htmlDoc.text().trim();
                if (summary.length() > 500) {
                    summary = summary.substring(0, 497) + "...";
                }
            } catch (Exception e) {
                log.debug("RssFeedCrawler: Failed to parse description HTML for: {}", title);
                summary = description.replaceAll("<[^>]+>", "").trim();
            }
        }

        String publishedDate = parsePublishedDate(pubDateStr);

        return CrawledArticle.builder()
                .title(title)
                .summary(summary)
                .url(link)
                .sourceUrl(source.getUrl())
                .sourceName(source.getName())
                .thumbnail(thumbnail)
                .publishedDate(publishedDate)
                .language(source.getLanguage())
                .aiProcessingStatus("PENDING")
                .build();
    }

    /**
     * Extract text content from a child XML element by tag name.
     */
    private String getElementText(org.w3c.dom.Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() > 0) {
            String text = nodes.item(0).getTextContent();
            return text != null ? text.trim() : null;
        }
        return null;
    }

    /**
     * Parse RSS pubDate (RFC 2822 format) into ISO date string.
     * Handles multiple date format variants used by Vietnamese and international feeds.
     */
    private String parsePublishedDate(String pubDateStr) {
        if (pubDateStr == null || pubDateStr.isBlank()) {
            return LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        }

        // Normalize timezone offset: "+07" → "+0700"
        String normalized = pubDateStr.trim();
        if (normalized.matches(".*[+-]\\d{2}$")) {
            normalized += "00";
        }

        // Try multiple date formats
        DateTimeFormatter[] formatters = {
                DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH),
                DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss Z", Locale.ENGLISH),
                DateTimeFormatter.ofPattern("EEE, dd MMM yy HH:mm:ss Z", Locale.ENGLISH),
                DateTimeFormatter.ofPattern("EEE, d MMM yy HH:mm:ss Z", Locale.ENGLISH),
        };

        for (DateTimeFormatter formatter : formatters) {
            try {
                ZonedDateTime zdt = ZonedDateTime.parse(normalized, formatter);
                return zdt.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
            } catch (DateTimeParseException ignored) {
                // Try next format
            }
        }

        log.warn("RssFeedCrawler: Could not parse date '{}', using today", pubDateStr);
        return LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
    }
}
