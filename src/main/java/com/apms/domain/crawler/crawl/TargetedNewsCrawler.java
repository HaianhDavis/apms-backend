package com.apms.domain.crawler.crawl;

import com.apms.domain.crawler.domain.CrawledArticle;
import com.apms.domain.crawler.domain.TrackedCompany;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class TargetedNewsCrawler {

    private static final Pattern VIETNAMNET_IMAGE_PATTERN =
            Pattern.compile("https?://[^\"'\\s<>]+(?:vnncdn\\.net|vietnamnet\\.vn)[^\"'\\s<>]+?\\.(?:jpg|jpeg|png|webp)(?:\\?[^\"'\\s<>]*)?",
                    Pattern.CASE_INSENSITIVE);

    private static final List<NewsSearchSource> SOURCES = List.of(
            new NewsSearchSource(
                    "CafeF",
                    "https://cafef.vn",
                    List.of("https://news.google.com/rss/search?q=site:cafef.vn%%20%s%%20when:7d&hl=vi&gl=VN&ceid=VN:vi"),
                    "vi",
                    true),
            new NewsSearchSource(
                    "VietnamNet",
                    "https://vietnamnet.vn",
                    List.of("https://news.google.com/rss/search?q=site:vietnamnet.vn%%20%s%%20when:7d&hl=vi&gl=VN&ceid=VN:vi"),
                    "vi",
                    true),
            new NewsSearchSource(
                    "VnExpress",
                    "https://vnexpress.net",
                    List.of("https://news.google.com/rss/search?q=site:vnexpress.net%%20%s%%20when:7d&hl=vi&gl=VN&ceid=VN:vi"),
                    "vi",
                    true),
            new NewsSearchSource(
                    "TuoiTre",
                    "https://tuoitre.vn",
                    List.of("https://news.google.com/rss/search?q=site:tuoitre.vn%%20%s%%20when:7d&hl=vi&gl=VN&ceid=VN:vi"),
                    "vi",
                    true),
            new NewsSearchSource(
                    "ThanhNien",
                    "https://thanhnien.vn",
                    List.of("https://news.google.com/rss/search?q=site:thanhnien.vn%%20%s%%20when:7d&hl=vi&gl=VN&ceid=VN:vi"),
                    "vi",
                    true),
            new NewsSearchSource(
                    "BaoDauTu",
                    "https://baodautu.vn",
                    List.of("https://news.google.com/rss/search?q=site:baodautu.vn%%20%s%%20when:7d&hl=vi&gl=VN&ceid=VN:vi"),
                    "vi",
                    true)
    );

    private final RestClient restClient;

    public TargetedNewsCrawler(RestClient restClient) {
        this.restClient = restClient;
    }

    public List<CrawledArticle> crawl(List<TrackedCompany> trackedCompanies, int maxArticlesPerCompanyPerSource) {
        Map<String, CrawledArticle> articlesByUrl = new LinkedHashMap<>();

        for (TrackedCompany company : trackedCompanies) {
            List<String> namesToSearch = new ArrayList<>();
            namesToSearch.add(company.getCompanyName());
            if (company.getAliases() != null) {
                namesToSearch.addAll(company.getAliases());
            }

            for (String searchName : namesToSearch) {
                for (NewsSearchSource source : SOURCES) {
                    List<CrawledArticle> articles = crawlCompanyFromSource(company, searchName, source, maxArticlesPerCompanyPerSource);
                    for (CrawledArticle article : articles) {
                        articlesByUrl.putIfAbsent(article.getUrl(), article);
                    }
                }
            }
        }

        return new ArrayList<>(articlesByUrl.values());
    }

    private List<CrawledArticle> crawlCompanyFromSource(
            TrackedCompany company,
            String searchName,
            NewsSearchSource source,
            int maxArticles) {

        log.info("TargetedNewsCrawler: Searching '{}' for '{}'", source.name(), searchName);

        Map<String, CrawledArticle> articlesByUrl = new LinkedHashMap<>();
        for (String template : source.searchUrlTemplates()) {
            if (articlesByUrl.size() >= maxArticles) {
                break;
            }

            String searchUrl = template.formatted(urlEncode(searchName));
            try {
                String body = restClient.get()
                        .uri(searchUrl)
                        .retrieve()
                        .body(String.class);

                if (body == null || body.isBlank()) {
                    log.warn("TargetedNewsCrawler: Empty search response from '{}' for '{}'",
                            source.name(), searchName);
                    continue;
                }

                List<CrawledArticle> articles = looksLikeRss(body)
                        ? parseRssSearchResults(body, source, company, maxArticles - articlesByUrl.size(), searchUrl)
                        : parseSearchResults(body, source, company, maxArticles - articlesByUrl.size(), searchUrl);

                for (CrawledArticle article : articles) {
                    articlesByUrl.putIfAbsent(article.getUrl(), article);
                }
            } catch (Exception e) {
                log.warn("TargetedNewsCrawler: Search failed for '{}' on '{}' via '{}': {}",
                        searchName, source.name(), searchUrl, e.getMessage());
            }
        }

        return new ArrayList<>(articlesByUrl.values());
    }

    private List<CrawledArticle> parseSearchResults(
            String html,
            NewsSearchSource source,
            TrackedCompany company,
            int maxArticles,
            String searchUrl) {

        Document doc = Jsoup.parse(html, source.baseUrl());
        List<CrawledArticle> articles = new ArrayList<>();

        for (Element link : doc.select("a[href]")) {
            if (articles.size() >= maxArticles) {
                break;
            }

            String href = normalizeUrl(link.absUrl("href"), source);
            String title = link.text() != null ? link.text().trim() : "";

            if (!isArticleUrl(href, source) || title.length() < 12) {
                continue;
            }

            Element container = findResultContainer(link);
            String summary = extractSummary(container, title);
            String thumbnail = extractThumbnail(container);
            if (thumbnail == null) {
                thumbnail = fetchArticleImage(href);
            }

            if (!mentionsCompany(company, title + " " + summary + " " + href)) {
                continue;
            }

            articles.add(CrawledArticle.builder()
                    .title(title)
                    .summary(summary)
                    .url(href)
                    .sourceUrl(searchUrl)
                    .sourceName(source.name())
                    .thumbnail(thumbnail)
                    .publishedDate(LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE))
                    .language(source.language())
                    .aiProcessingStatus("PENDING")
                    .build());
        }

        log.info("TargetedNewsCrawler: '{}' returned {} candidate article(s) for '{}'",
                source.name(), articles.size(), company.getCompanyName());
        return articles;
    }

    private List<CrawledArticle> parseRssSearchResults(
            String xml,
            NewsSearchSource source,
            TrackedCompany company,
            int maxArticles,
            String searchUrl) {

        List<CrawledArticle> articles = new ArrayList<>();

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            org.w3c.dom.Document doc = builder.parse(new InputSource(new StringReader(xml)));
            NodeList items = doc.getElementsByTagName("item");

            for (int i = 0; i < items.getLength() && articles.size() < maxArticles; i++) {
                org.w3c.dom.Element item = (org.w3c.dom.Element) items.item(i);
                String title = getElementText(item, "title");
                String link = normalizeGoogleNewsUrl(getElementText(item, "link"));
                String description = Jsoup.parse(getElementText(item, "description") != null
                        ? getElementText(item, "description")
                        : "").text();
                String thumbnail = fetchArticleImage(link);

                if (!isRssArticleUrl(link, source) || !mentionsCompany(company, title + " " + description + " " + link)) {
                    continue;
                }

                articles.add(CrawledArticle.builder()
                        .title(cleanGoogleNewsTitle(title))
                        .summary(description)
                        .url(link)
                        .sourceUrl(searchUrl)
                        .sourceName(source.name())
                        .thumbnail(thumbnail)
                        .publishedDate(LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE))
                        .language(source.language())
                        .aiProcessingStatus("PENDING")
                        .build());
            }
        } catch (Exception e) {
            log.warn("TargetedNewsCrawler: Failed to parse RSS search results for '{}' on '{}': {}",
                    company.getCompanyName(), source.name(), e.getMessage());
        }

        log.info("TargetedNewsCrawler: '{}' RSS returned {} candidate article(s) for '{}'",
                source.name(), articles.size(), company.getCompanyName());
        return articles;
    }

    private Element findResultContainer(Element link) {
        Element current = link;
        for (int i = 0; i < 4 && current != null; i++) {
            if (current.selectFirst("img") != null || current.text().length() > link.text().length() + 30) {
                return current;
            }
            current = current.parent();
        }
        return link.parent();
    }

    private String extractSummary(Element container, String title) {
        if (container == null) {
            return title;
        }

        String text = container.text().trim();
        if (text.startsWith(title)) {
            text = text.substring(title.length()).trim();
        }
        if (text.isBlank()) {
            return title;
        }
        return text.length() > 500 ? text.substring(0, 497) + "..." : text;
    }

    private String extractThumbnail(Element container) {
        if (container == null) {
            return null;
        }
        Element image = container.selectFirst("img[src], img[data-src]");
        if (image == null) {
            return null;
        }
        String src = image.hasAttr("data-src") ? image.absUrl("data-src") : image.absUrl("src");
        return src == null || src.isBlank() ? null : src;
    }

    private String fetchArticleImage(String articleUrl) {
        if (articleUrl == null || articleUrl.isBlank() || articleUrl.startsWith("https://news.google.com/")) {
            return null;
        }

        try {
            String html = restClient.get()
                    .uri(articleUrl)
                    .retrieve()
                    .body(String.class);

            if (html == null || html.isBlank()) {
                return null;
            }

            Document doc = Jsoup.parse(html, articleUrl);
            Element metaImage = doc.selectFirst(
                    "meta[property=og:image], meta[name=og:image], meta[name=twitter:image], meta[property=twitter:image]");
            if (metaImage != null) {
                String content = metaImage.attr("content");
                if (content != null && !content.isBlank()) {
                    return resolveUrl(articleUrl, content.trim());
                }
            }

            Element articleImage = doc.selectFirst(
                    "article img[src], article img[data-src], article img[data-original], " +
                            ".maincontent img[src], .maincontent img[data-src], .maincontent img[data-original], " +
                            ".content-detail img[src], .content-detail img[data-src], .content-detail img[data-original], " +
                            "source[srcset], img[src], img[data-src], img[data-original], img[srcset]");
            String imageUrl = extractImageUrl(articleImage, articleUrl);
            if (imageUrl != null) {
                return imageUrl;
            }

            Matcher matcher = VIETNAMNET_IMAGE_PATTERN.matcher(html);
            if (matcher.find()) {
                return matcher.group().replace("\\/", "/");
            }
        } catch (Exception e) {
            log.debug("TargetedNewsCrawler: Failed to fetch article image from '{}': {}",
                    articleUrl, e.getMessage());
        }

        return null;
    }

    private String extractImageUrl(Element imageElement, String baseUrl) {
        if (imageElement == null) {
            return null;
        }

        String[] attributes = {"data-src", "data-original", "src", "srcset"};
        for (String attribute : attributes) {
            String value = imageElement.attr(attribute);
            if (value == null || value.isBlank()) {
                continue;
            }

            if ("srcset".equals(attribute)) {
                value = value.split("\\s+")[0].split(",")[0];
            }

            String resolved = resolveUrl(baseUrl, value.trim());
            if (!resolved.isBlank() && !resolved.contains("logo") && !resolved.contains("icon")) {
                return resolved;
            }
        }

        return null;
    }

    private String resolveUrl(String baseUrl, String maybeRelativeUrl) {
        try {
            return URI.create(baseUrl).resolve(maybeRelativeUrl).toString();
        } catch (Exception ignored) {
            return maybeRelativeUrl;
        }
    }

    private String normalizeUrl(String href, NewsSearchSource source) {
        if (href == null) {
            return "";
        }
        int hashIndex = href.indexOf('#');
        if (hashIndex >= 0) {
            href = href.substring(0, hashIndex);
        }
        return href.trim();
    }

    private boolean isArticleUrl(String href, NewsSearchSource source) {
        if (href == null || !href.startsWith(source.baseUrl())) {
            return false;
        }
        String lower = href.toLowerCase(Locale.ROOT);
        if (lower.contains("/tag/") || lower.contains("/tim-kiem") || lower.contains("/rss")) {
            return false;
        }
        return lower.endsWith(".chn") || lower.matches(".*-\\d+\\.html?$") || lower.contains("-20");
    }

    private boolean isRssArticleUrl(String href, NewsSearchSource source) {
        if (source.allowRssFallback() && href != null && href.startsWith("https://news.google.com/")) {
            return true;
        }
        return isArticleUrl(href, source);
    }

    private boolean mentionsCompany(TrackedCompany company, String text) {
        String normalizedText = normalize(text);
        for (String keyword : buildStrictKeywords(company)) {
            if (normalizedText.contains(normalize(keyword))) {
                return true;
            }
        }
        return false;
    }

    private List<String> buildStrictKeywords(TrackedCompany company) {
        List<String> keywords = new ArrayList<>();
        keywords.add(company.getCompanyName());
        if (company.getAliases() != null) keywords.addAll(company.getAliases());
        if (company.getSubsidiaries() != null) keywords.addAll(company.getSubsidiaries());
        return keywords;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private boolean looksLikeRss(String body) {
        String trimmed = body.stripLeading().toLowerCase(Locale.ROOT);
        return trimmed.startsWith("<?xml") || trimmed.startsWith("<rss") || trimmed.contains("<rss");
    }

    private String getElementText(org.w3c.dom.Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() == 0 || nodes.item(0).getTextContent() == null) {
            return null;
        }
        return nodes.item(0).getTextContent().trim();
    }

    private String cleanGoogleNewsTitle(String title) {
        if (title == null) {
            return "";
        }
        return title.replaceFirst("\\s+-\\s+VietnamNet.*$", "").trim();
    }

    private String normalizeGoogleNewsUrl(String url) {
        return url == null ? "" : url.trim();
    }

    private record NewsSearchSource(
            String name,
            String baseUrl,
            List<String> searchUrlTemplates,
            String language,
            boolean allowRssFallback) {
    }
}

