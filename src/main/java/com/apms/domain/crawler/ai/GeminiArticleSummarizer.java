package com.apms.domain.crawler.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.apms.domain.crawler.domain.CrawledArticle;
import com.apms.domain.crawler.repository.CrawledArticleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class GeminiArticleSummarizer {

    private static final String GEMINI_API_URL =
            "https://generativelanguage.googleapis.com/v1/models/{model}:generateContent?key={key}";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final CrawledArticleRepository articleRepository;

    @Value("${crawler.ai.summary.enabled:true}")
    private boolean summaryEnabled;

    @Value("${crawler.ai.gemini.api-key:dummy-key}")
    private String geminiApiKey;

    @Value("${crawler.ai.gemini.model:gemini-3.6-flash}")
    private String geminiModel;

    @Value("${crawler.ai.summary.max-articles-per-run:20}")
    private int maxArticlesPerRun;

    public CrawledArticle summarizeArticle(CrawledArticle article, boolean forceRefresh) {
        if (!summaryEnabled) {
            throw new IllegalStateException("AI summary is disabled.");
        }
        if (isMockMode()) {
            throw new IllegalStateException("Gemini API key is not configured.");
        }
        if (!forceRefresh && article.getAiSummary() != null && !article.getAiSummary().isBlank()) {
            return article;
        }

        String sourceText = sourceText(article);
        if (sourceText.isBlank()) {
            throw new IllegalStateException("Article does not have enough content to summarize.");
        }

        try {
            String summary = summarize(article.getTitle(), sourceText);
            if (summary == null || summary.isBlank()) {
                throw new IllegalStateException("Gemini returned an empty summary.");
            }
            article.setAiSummary(summary);
            return articleRepository.save(article);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 429) {
                throw new IllegalStateException(
                        "Gemini Ä‘Ã£ háº¿t quota hoáº·c Ä‘ang bá»‹ rate limit. Vui lÃ²ng thá»­ láº¡i sau, Ä‘á»•i API key, hoáº·c nÃ¢ng quota/billing.");
            }
            throw new IllegalStateException("Gemini API lá»—i: " + e.getStatusCode(), e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to summarize article: " + e.getMessage(), e);
        }
    }

    public int summarizeReadyArticles() {
        if (!summaryEnabled || isMockMode()) {
            log.debug("GeminiArticleSummarizer: AI summary disabled or missing API key.");
            return 0;
        }

        List<CrawledArticle> articles = articleRepository
                .findByAiProcessingStatusInAndAiSummaryIsNull(List.of("MATCHED", "PUBLISHED"));

        int summarized = 0;
        for (CrawledArticle article : articles) {
            if (summarized >= maxArticlesPerRun) {
                break;
            }

            String sourceText = sourceText(article);
            if (sourceText.isBlank()) {
                continue;
            }

            try {
                summarizeArticle(article, false);
                summarized++;
            } catch (Exception e) {
                log.warn("GeminiArticleSummarizer: Failed to summarize '{}': {}",
                        article.getTitle(), e.getMessage());
            }
        }

        log.info("GeminiArticleSummarizer: Summarized {} article(s)", summarized);
        return summarized;
    }

    private String summarize(String title, String content) throws Exception {
        String prompt = """
        Ban la mot bien tap vien chuyen tong hop tin tuc doanh nghiep.

        Nhiem vu:
        Doc tieu de va noi dung bai bao, sau do viet mot doan tom tat bang tieng Viet de hien thi tren feed tin tuc.

        Quy tac:
        1. Gom 2-3 cau ngan gon (khoang 50-80 tu).
        2. Cau dau tien phai neu su kien chinh cua bai bao.
        3. Cau tiep theo neu cong ty/doanh nghiep lien quan va nhung thong tin quan trong nhat.
        4. Neu bai bao co noi ve anh huong, ket qua, chien luoc, doanh thu, dau tu, san pham moi, hop tac... thi tom tat ngan gon tac dong do.
        5. Khong dua y kien ca nhan.
        6. Khong bo sung kien thuc ngoai bai bao.
        7. Khong lap lai tieu de.
        8. Khong dung markdown, emoji hay gach dau dong.
        9. Chi tra ve mot doan van duy nhat.

        Tieu de:
        %s

        Noi dung:
        %s
        """.formatted(title != null ? title : "", trim(content, 5000));
        Map<String, Object> requestPayload = Map.of(
                "contents", List.of(
                        Map.of("parts", List.of(
                                Map.of("text", prompt)
                        ))
                )
        );

        String responseBody = restClient.post()
                .uri(GEMINI_API_URL, geminiModel, geminiApiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestPayload)
                .retrieve()
                .body(String.class);

        JsonNode rootNode = objectMapper.readTree(responseBody);
        return rootNode.path("candidates")
                .get(0)
                .path("content")
                .path("parts")
                .get(0)
                .path("text")
                .asText("")
                .trim();
    }

    private String sourceText(CrawledArticle article) {
        String content = article.getContent();
        if (content != null && !content.isBlank()) {
            return content;
        }
        String summary = article.getSummary();
        return summary != null ? summary : "";
    }

    private String trim(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value != null ? value : "";
        }
        return value.substring(0, maxLength);
    }

    private boolean isMockMode() {
        return "dummy-key".equals(geminiApiKey) ||
                geminiApiKey == null ||
                geminiApiKey.isBlank();
    }
}

