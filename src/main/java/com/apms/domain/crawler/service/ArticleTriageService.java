package com.apms.domain.crawler.service;

import com.apms.domain.crawler.domain.CrawledArticle;
import com.apms.domain.crawler.repository.CrawledArticleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ArticleTriageService {

    private final CrawledArticleRepository articleRepository;

    private static final List<String> NEGATIVE_WORDS = List.of(
            "lo ngai", "rui ro", "giam manh", "lao doc", "mat gia", "lo nang", "thua lo",
            "cat giam", "sa thai", "khoi kien", "vu kien", "bi phat", "dieu tra", "vi pham", "su co",
            "tan cong", "ro ri", "ngung", "loi", "canh bao", "ap luc"
    );

    private static final List<String> POSITIVE_WORDS = List.of(
            "tang", "lai", "ky luc", "dot pha", "hop tac", "dau tu", "ra mat", "mo rong",
            "tang truong", "thang lon", "dan dau", "cap phep", "van hanh", "trien khai",
            "hoan thanh", "thanh cong"
    );

    public CrawledArticle triage(CrawledArticle article) {
        String text = normalize(article.getTitle() + " " + article.getSummary() + " " + article.getContent());

        String type = detectInformationType(text);
        String sentiment = detectSentiment(text);
        int score = calculatePriorityScore(article, text, type, sentiment);

        article.setInformationType(type);
        article.setSentiment(sentiment);
        article.setPriorityScore(score);
        article.setPriorityLevel(score >= 80 ? "HIGH" : score >= 50 ? "MEDIUM" : "LOW");
        article.setPriorityReason(buildReason(type, sentiment, score));
        return article;
    }

    public int backfillMissingTriage() {
        List<CrawledArticle> articles = articleRepository.findAll();
        int updated = 0;
        for (CrawledArticle article : articles) {
            articleRepository.save(triage(article));
            updated++;
        }
        return updated;
    }

    private String detectInformationType(String text) {
        if (hasAny(text, "doanh thu", "loi nhuan", "lai", "von hoa", "co phieu", "tai chinh")) return "FINANCIAL";
        if (hasAny(text, "ai", "chip", "du lieu", "cong nghe", "5g", "cloud", "phan mem", "bao mat")) return "TECHNOLOGY";
        if (hasAny(text, "khoi kien", "vu kien", "phap ly", "dieu tra", "vi pham", "bi phat", "toa an")) return "LEGAL";
        if (hasAny(text, "ra mat", "san pham", "smartphone", "nen tang", "dich vu")) return "PRODUCT";
        if (hasAny(text, "thi truong", "xuat khau", "trung quoc", "my", "canh tranh", "kien nghi", "de xuat", "co che", "chinh sach")) return "MARKET";
        if (hasAny(text, "ceo", "chu tich", "tong giam doc", "lanh dao", "sep")) return "LEADERSHIP";
        if (hasAny(text, "hop tac", "doi tac", "lien minh", "ky ket")) return "PARTNERSHIP";
        if (hasAny(text, "su co", "tan cong", "ro ri", "loi", "ngung hoat dong")) return "INCIDENT";
        return "GENERAL";
    }

    private String detectSentiment(String text) {
        int negative = countMatches(text, NEGATIVE_WORDS);
        int positive = countMatches(text, POSITIVE_WORDS);
        if (negative > positive) return "NEGATIVE";
        if (positive > negative) return "POSITIVE";
        return "NEUTRAL";
    }

    private int calculatePriorityScore(CrawledArticle article, String text, String type, String sentiment) {
        int score = 20;
        score += Math.min(article.getMatchedCompanies() != null ? article.getMatchedCompanies().size() * 12 : 0, 30);

        if ("NEGATIVE".equals(sentiment)) score += 30;
        if ("POSITIVE".equals(sentiment)) score += 18;

        if (List.of("LEGAL", "INCIDENT", "FINANCIAL", "TECHNOLOGY").contains(type)) score += 20;
        if (List.of("MARKET", "PRODUCT", "LEADERSHIP", "PARTNERSHIP").contains(type)) score += 12;

        if (hasAny(text, "nvidia", "microsoft", "samsung", "viettel", "fpt", "vnpt")) score += 10;

        return Math.min(score, 100);
    }

    private String buildReason(String type, String sentiment, int score) {
        return "Type=" + type + ", sentiment=" + sentiment + ", priorityScore=" + score;
    }

    private boolean hasAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (containsKeyword(text, keyword)) return true;
        }
        return false;
    }

    private int countMatches(String text, List<String> keywords) {
        int count = 0;
        for (String keyword : keywords) {
            if (containsKeyword(text, keyword)) count++;
        }
        return count;
    }

    private boolean containsKeyword(String text, String keyword) {
        String pattern = "(^|[^a-z0-9])" + Pattern.quote(keyword) + "($|[^a-z0-9])";
        return Pattern.compile(pattern).matcher(text).find();
    }

    private String normalize(String value) {
        if (value == null) return "";
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return normalized.toLowerCase(Locale.ROOT);
    }
}
