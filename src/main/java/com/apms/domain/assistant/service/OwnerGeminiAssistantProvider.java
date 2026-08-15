package com.apms.domain.assistant.service;

import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.assistant.dto.AssistantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class OwnerGeminiAssistantProvider implements AssistantProvider {

    private static final String GEMINI_API_URL =
            "https://generativelanguage.googleapis.com/v1/models/{model}:generateContent?key={key}";

    private static final String EXECUTIVE_SYSTEM_PROMPT = """
            You are an elite APMS (Acquisition & Partnership Management System) executive AI assistant.
            You are directly assisting a Business Owner.

            CRITICAL RULES:
            1. Answer ONLY using the approved APMS data provided in the context below.
            2. Do NOT use any external knowledge or general knowledge about companies.
            3. Do NOT invent or assume any facts not present in the provided context.
            4. If the context does not contain enough information to answer the question, explicitly state:
               "The APMS system does not have enough approved information to answer this question."
            5. For general company profile questions (e.g., "What do we know about this company?"), provide a STRICTLY factual summary (e.g., Legal Name, Industries, Business Model, Strengths). Do NOT use unsupported strategic language or invent stronger claims.
            6. Reserve strategic synthesis for explicit strategic questions (e.g., "What should we do strategically?").
            7. Do NOT append any fabricated "Data Source:" lines, source labels, or references in the text. The UI handles sources independently.
            8. Format output with clear markdown headings and bullet points.
            """;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String geminiApiKey;
    private final String geminiModel;

    public OwnerGeminiAssistantProvider(
            ObjectMapper objectMapper,
            @Value("${app.ai.gemini.api-key:dummy-key}") String geminiApiKey,
            @Value("${app.ai.gemini.model:gemini-3.6-flash}") String geminiModel) {
        this.restClient = RestClient.builder().build();
        this.objectMapper = objectMapper;
        this.geminiApiKey = geminiApiKey;
        this.geminiModel = geminiModel;
    }

    @Override
    public String answer(String question, AssistantContext context) {
        boolean useMock = !StringUtils.hasText(geminiApiKey) || "dummy-key".equals(geminiApiKey);
        if (useMock) {
            log.info("Gemini API key not configured. Using mock owner assistant response.");
            return buildMockAnswer(question, context);
        }

        String fullPrompt = buildPrompt(question, context);

        Map<String, Object> requestPayload = Map.of(
                "contents", List.of(
                        Map.of("parts", List.of(
                                Map.of("text", fullPrompt)
                        ))
                )
        );

        int maxRetries = 3;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                String responseBody = restClient.post()
                        .uri(GEMINI_API_URL, geminiModel, geminiApiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(requestPayload)
                        .retrieve()
                        .body(String.class);

                JsonNode rootNode = objectMapper.readTree(responseBody);
                String answer = rootNode
                        .path("candidates").get(0)
                        .path("content").path("parts").get(0)
                        .path("text").asText();

                return answer.trim();

            } catch (RestClientResponseException e) {
                if (e.getStatusCode().value() == 429 && attempt < maxRetries) {
                    log.warn("Gemini rate limit (429). Attempt {}/{}. Backing off...", attempt, maxRetries);
                    try {
                        Thread.sleep((long) Math.pow(2, attempt) * 1000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new BusinessValidationException("Assistant request interrupted.");
                    }
                } else {
                    log.error("Gemini assistant call failed (attempt {}): {}", attempt, e.getResponseBodyAsString());
                    return buildMockAnswer(question, context);
                }
            } catch (Exception e) {
                log.error("Gemini assistant parsing failed (attempt {})", attempt, e);
                return buildMockAnswer(question, context);
            }
        }
        return buildMockAnswer(question, context);
    }

    private String buildPrompt(String question, AssistantContext context) {
        return EXECUTIVE_SYSTEM_PROMPT
                + "\n\n=== EXECUTIVE APMS CONTEXT ===\n"
                + context.getContextText()
                + "\n=== USER QUESTION ===\n"
                + question;
    }

    private String buildMockAnswer(String question, AssistantContext context) {
        StringBuilder answer = new StringBuilder();
        answer.append("Based on the approved APMS context:\n\n");

        if (context.getCompanyProfile() != null) {
            String name = context.getCompanyProfile().getIdentity() != null
                ? context.getCompanyProfile().getIdentity().getLegalName()
                : "Unknown";
            answer.append("Focusing on specific company: **").append(name).append("**.\n");
        } else {
            answer.append("Providing an ecosystem executive summary for project ID: ").append(context.getProjectId()).append(".\n");
        }

        if (context.getFormattedRelationships() != null && !context.getFormattedRelationships().isEmpty()) {
            answer.append("- Tracked Relationships: ").append(context.getFormattedRelationships().size()).append("\n");
        }

        answer.append("\nQuestion received: ").append(question);
        return answer.toString();
    }
}
