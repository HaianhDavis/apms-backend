package com.apms.domain.ai.service.provider;

import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.ai.dto.RawExtractionOutput;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class GeminiExtractionProvider implements ExtractionProvider {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final AiExtractionResponseMapper responseMapper;
    private final String geminiApiKey;
    private final String geminiModel;
    private final String extractionSystemPrompt;

    private static final String GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1/models/{model}:generateContent?key={key}";

    public GeminiExtractionProvider(ObjectMapper objectMapper,
                                    AiExtractionResponseMapper responseMapper,
                                    @Value("${app.ai.gemini.api-key:dummy-key}") String geminiApiKey,
                                    @Value("${app.ai.gemini.model:gemini-2.5-flash}") String geminiModel) {
        this.restClient = RestClient.builder().build();
        this.objectMapper = objectMapper;
        this.responseMapper = responseMapper;
        this.geminiApiKey = geminiApiKey;
        this.geminiModel = geminiModel;
        this.extractionSystemPrompt = loadPrompt();
    }

    private String loadPrompt() {
        try {
            ClassPathResource resource = new ClassPathResource("ai-prompts/company-extraction.prompt.md");
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to load Gemini extraction prompt", e);
            throw new RuntimeException("Failed to load prompt", e);
        }
    }

    @Override
    public RawExtractionOutput extract(String sourceText) {
        String fullPrompt = extractionSystemPrompt + "\n\nText:\n" + sourceText;

        Map<String, Object> requestPayload = Map.of(
                "contents", List.of(
                        Map.of("parts", List.of(
                                Map.of("text", fullPrompt)
                        ))
                )
        );

        String rawAiOutput = "";
        int maxRetries = 3;
        int attempt = 0;

        while (attempt < maxRetries) {
            try {
                attempt++;
                String responseBody = restClient.post()
                        .uri(GEMINI_API_URL, geminiModel, geminiApiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(requestPayload)
                        .retrieve()
                        .body(String.class);

                // Parse the Gemini JSON structure to extract the text
                JsonNode rootNode = objectMapper.readTree(responseBody);
                rawAiOutput = rootNode.path("candidates")
                        .get(0)
                        .path("content")
                        .path("parts")
                        .get(0)
                        .path("text")
                        .asText();

                rawAiOutput = cleanMarkdownFences(rawAiOutput);

                return responseMapper.mapResponse(rawAiOutput);

            } catch (RestClientResponseException e) {
                if (e.getStatusCode().value() == 429) {
                    log.warn("Gemini API rate limit exceeded (429). Attempt {} of {}", attempt, maxRetries);
                    if (attempt >= maxRetries) {
                        log.error("Gemini API rate limit exceeded after {} attempts.", maxRetries, e);
                        throw new BusinessValidationException("Gemini AI rate limit exceeded.");
                    }
                    try {
                        Thread.sleep((long) Math.pow(2, attempt) * 1000); // exponential backoff
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new BusinessValidationException("Extraction interrupted");
                    }
                } else {
                    log.error("Gemini API call failed: {}", e.getResponseBodyAsString(), e);
                    throw new BusinessValidationException("Gemini API call failed: " + e.getStatusCode());
                }
            } catch (Exception e) {
                log.error("Gemini Extraction failed or parsing failed. Raw Output: {}", rawAiOutput, e);
                throw new BusinessValidationException("Failed to parse Gemini extraction output. Invalid JSON or mismatch.");
            }
        }
        throw new BusinessValidationException("Gemini extraction failed.");
    }

    private String cleanMarkdownFences(String rawOutput) {
        String clean = rawOutput.trim();
        if (clean.startsWith("```json")) {
            clean = clean.replaceFirst("```json", "");
        } else if (clean.startsWith("```")) {
            clean = clean.replaceFirst("```", "");
        }
        if (clean.endsWith("```")) {
            clean = clean.substring(0, clean.length() - 3);
        }
        return clean.trim();
    }
}
