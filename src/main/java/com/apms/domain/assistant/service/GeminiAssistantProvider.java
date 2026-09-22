package com.apms.domain.assistant.service;

import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.ai.service.provider.GeminiApiKeyManager;
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

            Your role is to assist users with both general business knowledge and specific APMS business intelligence.

            CRITICAL RULES:
            1. For GENERAL knowledge questions (e.g., "What is SWOT analysis?", "What are common risks?"), answer normally using your general knowledge. Do NOT claim this general knowledge comes from APMS data.
            2. For APMS-SPECIFIC questions, you must ONLY use the provided approved APMS data below.
            3. Do NOT invent, assume, or infer any APMS-specific facts not present in the provided context.
            4. If the user asks for APMS-specific information about a company/project/task that is not in the context, respond: "The APMS system does not have enough approved information to answer this question."
            5. If the context explicitly states the user is NOT AUTHORIZED to access requested APMS data, respond: "The requested APMS information is outside your available workspace scope."
            6. For MIXED questions, you may answer the general knowledge part, but apply the rules above to the APMS-specific part.
            7. Keep answers concise, business-focused, and professional.
            8. When referencing data, mention which source it comes from (e.g., "According to your active projects...", "Based on your assigned tasks...").
            9. Do NOT reveal internal system IDs unnecessarily unless asked.
            """;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final GeminiApiKeyManager geminiApiKeyManager;
    private final String geminiModel;

    public GeminiAssistantProvider(
            ObjectMapper objectMapper,
            GeminiApiKeyManager geminiApiKeyManager,
            @Value("${app.ai.gemini.model:gemini-3.6-flash}") String geminiModel) {
        this.restClient = RestClient.builder()
                .requestInterceptor(com.apms.domain.ai.service.provider.GeminiCredentialDiagnostics.interceptor("GeminiAssistantProvider")).build();
        this.objectMapper = objectMapper;
        this.geminiApiKeyManager = geminiApiKeyManager;
        this.geminiModel = geminiModel;
    }

    @Override
    public String answer(String question, AssistantContext context) {
        String geminiApiKey = geminiApiKeyManager.getApiKey();
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
            return "The APMS system does not have enough approved information to answer this question. "
                    + "No approved company profile was found in the current project context.";
        }

        String companyName = "the selected company";
        if (context.getCompanyProfile().getIdentity() != null) {
            String name = context.getCompanyProfile().getIdentity().getLegalName();
            if (StringUtils.hasText(name)) companyName = name;
        }

        StringBuilder answer = new StringBuilder();
//        answer.append("[MOCK RESPONSE — Gemini key not configured]\n\n");
        answer.append("Based on the approved APMS company profile for **").append(companyName).append("**:\n\n");

        if (context.getCompanyProfile().getBusiness() != null) {
            answer.append("- Industries: ").append(context.getCompanyProfile().getBusiness().getIndustries()).append("\n");
            answer.append("- Business Model: ").append(context.getCompanyProfile().getBusiness().getBusinessModel()).append("\n");
        }


        if (context.getFormattedRelationships() != null && !context.getFormattedRelationships().isEmpty()) {
            answer.append("- Known relationships: ").append(context.getFormattedRelationships().size()).append(" approved graph connection(s).\n");
        }

        answer.append("\nQuestion received: ").append(question);
        return answer.toString();
    }
}
