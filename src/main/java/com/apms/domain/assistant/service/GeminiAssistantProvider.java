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

/**
 * Gemini-backed implementation of AssistantProvider.
 *
 * Falls back to a deterministic mock answer when:
 *   - GEMINI_API_KEY is not set ("dummy-key")
 *   - The API call fails
 *
 * Prompt rules:
 *   - Answer ONLY from the provided APMS approved context
 *   - Do NOT use external knowledge
 *   - Do NOT invent facts
 *   - If context is insufficient, say so explicitly
 */
@Slf4j
@Component
public class GeminiAssistantProvider implements AssistantProvider {

    private static final String GEMINI_API_URL =
            "https://generativelanguage.googleapis.com/v1/models/{model}:generateContent?key={key}";

    private static final String ASSISTANT_SYSTEM_PROMPT = """
            You are an APMS (Acquisition & Partnership Management System) AI assistant.

            Your role is to answer business intelligence questions about companies in the APMS system.

            CRITICAL RULES:
            1. Answer ONLY using the approved APMS data provided in the context below.
            2. Do NOT use any external knowledge or general knowledge about companies.
            3. Do NOT invent or assume any facts not present in the provided context.
            4. If the context does not contain enough information to answer the question, respond:
               "The APMS system does not have enough approved information to answer this question."
            5. Keep answers concise, business-focused, and professional.
            6. When referencing data, mention which source it comes from
               (e.g., "According to the approved company profile...", "Based on the score snapshot...").
            7. Do NOT reveal internal system IDs unnecessarily unless asked.
            8. Prioritize the provided Neo4j relationships when answering relationship or classification questions.
            """;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String geminiApiKey;
    private final String geminiModel;

    public GeminiAssistantProvider(
            ObjectMapper objectMapper,
            @Value("${app.ai.gemini.api-key:dummy-key}") String geminiApiKey,
            @Value("${app.ai.gemini.model:gemini-2.5-flash}") String geminiModel) {
        this.restClient = RestClient.builder().build();
        this.objectMapper = objectMapper;
        this.geminiApiKey = geminiApiKey;
        this.geminiModel = geminiModel;
    }

    @Override
    public String answer(String question, AssistantContext context) {
        boolean useMock = !StringUtils.hasText(geminiApiKey) || "dummy-key".equals(geminiApiKey);
        if (useMock) {
            log.info("Gemini API key not configured. Using mock assistant response.");
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
        return ASSISTANT_SYSTEM_PROMPT
                + "\n\n=== APPROVED APMS CONTEXT ===\n"
                + context.getContextText()
                + "\n=== USER QUESTION ===\n"
                + question;
    }

    private String buildMockAnswer(String question, AssistantContext context) {
        if (context.getCompanyProfile() == null) {
            return "[MOCK] The APMS system does not have enough approved information to answer this question. "
                    + "No approved company profile was found in the current project context.";
        }

        String companyName = "the selected company";
        if (context.getCompanyProfile().getIdentity() != null) {
            String name = context.getCompanyProfile().getIdentity().getLegalName();
            if (StringUtils.hasText(name)) companyName = name;
        }

        StringBuilder answer = new StringBuilder();
        answer.append("[MOCK RESPONSE — Gemini key not configured]\n\n");
        answer.append("Based on the approved APMS company profile for **").append(companyName).append("**:\n\n");

        if (context.getCompanyProfile().getBusiness() != null) {
            answer.append("- Industries: ").append(context.getCompanyProfile().getBusiness().getIndustries()).append("\n");
            answer.append("- Business Model: ").append(context.getCompanyProfile().getBusiness().getBusinessModel()).append("\n");
        }

        if (context.getLatestScore() != null) {
            answer.append("- Total Score: ").append(context.getLatestScore().getTotalScore()).append("\n");
            answer.append("- Partner Fit: ").append(context.getLatestScore().getPartnerFitScore()).append("\n");
        }

        if (context.getFormattedRelationships() != null && !context.getFormattedRelationships().isEmpty()) {
            answer.append("- Known relationships: ").append(context.getFormattedRelationships().size()).append(" approved graph connection(s).\n");
        }

        answer.append("\nQuestion received: ").append(question);
        return answer.toString();
    }
}
