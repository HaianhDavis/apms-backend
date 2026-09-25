package com.apms.domain.crawler.crawl;

import com.apms.domain.crawler.domain.CrawledArticle;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public abstract class AbstractRssNewsSourceAdapter implements NewsSourceAdapter {

    private static final Pattern IMAGE_PATTERN = Pattern.compile("https?://[^\"'\\s<>]+?\\.(?:jpg|jpeg|png|webp)", Pattern.CASE_INSENSITIVE);

    protected final RestClient restClient;

    protected AbstractRssNewsSourceAdapter(RestClient restClient) {
        this.restClient = restClient;
    }

    protected abstract List<String> getRssUrls();

    @Override
    public SourceFetchResult fetchLatestArticles() {
        List<CrawledArticle> articles = new ArrayList<>();
        int fetchedCount = 0;
        boolean hasError = false;
        String lastErrorMsg = null;
        String lastErrorCode = null;
        
        for (String rssUrl : getRssUrls()) {
            try {
                ResponseEntity<byte[]> response = restClient.get()
                        .uri(rssUrl)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
                        .header("Accept", "application/rss+xml, application/xml, text/xml, */*")
                        .retrieve()
                        .toEntity(byte[].class);

                byte[] rawBytes = response.getBody();

                if (rawBytes == null || rawBytes.length == 0) {
                    log.warn("{}: Empty response from RSS: {}", getSourceName(), rssUrl);
                    hasError = true;
                    lastErrorCode = "EMPTY_RESPONSE";
                    lastErrorMsg = "Empty response body";
                    continue;
                }

                String xml = new String(rawBytes, java.nio.charset.StandardCharsets.UTF_8);

                // Detect obvious HTML
                if (xml.trim().toLowerCase().startsWith("<!doctype html") || xml.trim().toLowerCase().startsWith("<html")) {
                    log.warn("{}: Invalid feed response (HTML detected): {}", getSourceName(), rssUrl);
                    hasError = true;
                    lastErrorCode = "INVALID_FEED_RESPONSE";
                    lastErrorMsg = "Received HTML instead of XML";
                    continue;
                }

                // Normalize XML: find the first '<' which is usually part of '<?xml' or '<rss'
                int firstBracket = xml.indexOf('<');
                if (firstBracket > 0) {
                    xml = xml.substring(firstBracket);
                }

                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                
                DocumentBuilder builder = factory.newDocumentBuilder();
                builder.setErrorHandler(new ErrorHandler() {
                    @Override
                    public void warning(SAXParseException exception) {
                        log.debug("{}: SAX warning at line {}: {}", getSourceName(), exception.getLineNumber(), exception.getMessage());
                    }
                    @Override
                    public void error(SAXParseException exception) {
                        log.debug("{}: SAX error at line {}: {}", getSourceName(), exception.getLineNumber(), exception.getMessage());
                    }
                    @Override
                    public void fatalError(SAXParseException exception) throws SAXException {
                        throw exception;
                    }
                });
                
                org.w3c.dom.Document doc = builder.parse(new InputSource(new StringReader(xml)));
                NodeList items = doc.getElementsByTagName("item");

                for (int i = 0; i < items.getLength(); i++) {
                    org.w3c.dom.Element item = (org.w3c.dom.Element) items.item(i);
                    String title = repairMojibake(getElementText(item, "title"));
                    String link = getElementText(item, "link");
                    String descriptionHtml = getElementText(item, "description");
                    String description = descriptionHtml != null ? repairMojibake(Jsoup.parse(descriptionHtml).text()) : "";
                    
                    if (title == null || link == null) continue;
                    
                    String thumbnail = extractImageFromDescription(descriptionHtml);

                    CrawledArticle article = CrawledArticle.builder()
                            .title(title.trim())
                            .summary(description.trim())
                            .url(link.trim())
                            .sourceUrl(rssUrl)
                            .sourceName(getSourceName())
                            .thumbnail(thumbnail)
                            .publishedDate(LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE))
                            .language("vi")
                            .aiProcessingStatus("PENDING")
                            .build();

                    articles.add(article);
                    fetchedCount++;
                }
            } catch (Exception e) {
                log.warn("{}: Failed to fetch or parse RSS {}: {}", getSourceName(), rssUrl, e.getMessage());
                hasError = true;
                lastErrorCode = e instanceof SAXException ? "INVALID_XML" : (e instanceof RestClientException ? "HTTP_ERROR" : "UNKNOWN_ERROR");
                lastErrorMsg = e.getMessage();
            }
        }
        
        String status = (articles.isEmpty() && hasError) ? "FAILED" : "SUCCESS";
        if (status.equals("SUCCESS") && hasError) {
             status = "PARTIAL_SUCCESS";
        }
        if (articles.isEmpty() && hasError) {
             log.warn("{}: Source failed completely", getSourceName());
        } else {
             log.info("{}: Fetched {} articles", getSourceName(), fetchedCount);
        }
        
        return SourceFetchResult.builder()
                .sourceName(getSourceName())
                .status(status)
                .fetchedCount(fetchedCount)
                .articles(articles)
                .errorCode(lastErrorCode)
                .errorMessage(lastErrorMsg)
                .build();
    }

    private String getElementText(org.w3c.dom.Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() == 0 || nodes.item(0).getTextContent() == null) {
            return null;
        }
        return nodes.item(0).getTextContent().trim();
    }
    
    private String extractImageFromDescription(String descriptionHtml) {
        if (descriptionHtml == null) return null;
        Document doc = Jsoup.parse(descriptionHtml);
        Element img = doc.selectFirst("img");
        if (img != null && img.hasAttr("src")) {
            return img.attr("src");
        }
        Matcher matcher = IMAGE_PATTERN.matcher(descriptionHtml);
        if (matcher.find()) {
            return matcher.group();
        }
        return null;
    }

    private static String repairMojibake(String text) {
        if (text == null || text.isBlank()) return text;
        if (text.contains("Ã") || text.contains("á»") || text.contains("Ä") || text.contains("Æ°") || text.contains("â") || text.contains("Â")) {
            try {
                byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
                String repaired = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                if (!repaired.contains("\uFFFD")) {
                    return repaired;
                }
            } catch (Exception ignored) {}
        }
        return text;
    }
}
