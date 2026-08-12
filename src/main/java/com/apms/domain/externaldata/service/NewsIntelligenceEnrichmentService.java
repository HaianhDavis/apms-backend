package com.apms.domain.externaldata.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Enriches public news metadata without treating model output as a source of fact. */
@Slf4j
@Service
public class NewsIntelligenceEnrichmentService {

    private static final List<String> ALLOWED_TOPICS = List.of(
            "COMPANY_NEWS", "THREAT", "MARKET_EXPANSION", "HIRING", "STRATEGIC_ACTIVITY");

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final String openAiApiKey;

    public NewsIntelligenceEnrichmentService(
            ChatClient.Builder chatClientBuilder,
            ObjectMapper objectMapper,
            @Value("${spring.ai.openai.api-key:dummy-key}") String openAiApiKey) {
        this.chatClient = chatClientBuilder.build();
        this.objectMapper = objectMapper;
        this.openAiApiKey = openAiApiKey;
    }

    public Optional<NewsIntelligenceAnalysis> analyze(String title, String summary) {
        if (!StringUtils.hasText(openAiApiKey) || "dummy-key".equals(openAiApiKey)) {
            return Optional.empty();
        }

        String sourceText = ((title == null ? "" : title) + "\n" + (summary == null ? "" : summary)).trim();
        if (!StringUtils.hasText(sourceText)) return Optional.empty();

        try {
            String response = chatClient.prompt()
                    .system("""
                            You classify public company-news metadata. Use only the supplied text and do not invent facts.
                            Return valid JSON only with: summary (one factual sentence), sentiment (POSITIVE|NEGATIVE|NEUTRAL),
                            riskLevel (HIGH|MEDIUM|LOW), opportunityLevel (HIGH|MEDIUM|LOW), and topics.
                            topics must be a subset of COMPANY_NEWS, THREAT, MARKET_EXPANSION, HIRING, STRATEGIC_ACTIVITY.
                            Use COMPANY_NEWS when no other topic is supported by the text.
                            """)
                    .user("Article metadata:\n" + truncate(sourceText, 6000))
                    .call()
                    .content();

            NewsIntelligenceAnalysis analysis = objectMapper.readValue(stripMarkdownFence(response), NewsIntelligenceAnalysis.class);
            return Optional.of(sanitize(analysis));
        } catch (Exception ex) {
            log.warn("AI enrichment failed; retaining deterministic news classification", ex);
            return Optional.empty();
        }
    }

    private NewsIntelligenceAnalysis sanitize(NewsIntelligenceAnalysis analysis) {
        List<String> topics = analysis.topics() == null ? List.of() : analysis.topics().stream()
                .filter(StringUtils::hasText)
                .map(topic -> topic.trim().toUpperCase(Locale.ROOT))
                .filter(ALLOWED_TOPICS::contains)
                .distinct()
                .toList();
        if (topics.isEmpty()) topics = List.of("COMPANY_NEWS");
        return new NewsIntelligenceAnalysis(
                analysis.summary(),
                normalizeLevel(analysis.sentiment(), "NEUTRAL", List.of("POSITIVE", "NEGATIVE", "NEUTRAL")),
                normalizeLevel(analysis.riskLevel(), "LOW", List.of("HIGH", "MEDIUM", "LOW")),
                normalizeLevel(analysis.opportunityLevel(), "LOW", List.of("HIGH", "MEDIUM", "LOW")),
                topics);
    }

    private String normalizeLevel(String value, String fallback, List<String> allowed) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return allowed.contains(normalized) ? normalized : fallback;
    }

    private String stripMarkdownFence(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (!trimmed.startsWith("```")) return trimmed;
        int firstNewLine = trimmed.indexOf('\n');
        int lastFence = trimmed.lastIndexOf("```");
        return firstNewLine >= 0 && lastFence > firstNewLine
                ? trimmed.substring(firstNewLine + 1, lastFence).trim()
                : trimmed;
    }

    private String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    public record NewsIntelligenceAnalysis(
            String summary,
            String sentiment,
            String riskLevel,
            String opportunityLevel,
            List<String> topics) {
    }
}
