package com.apms.domain.ai.service.provider;

import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.ai.dto.ExtractedCompanyData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class GeminiExtractionProvider implements ExtractionProvider {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String geminiApiKey;
    private final String geminiModel;

    private static final String GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1/models/{model}:generateContent?key={key}";

    private static final String EXTRACTION_SYSTEM_PROMPT = """
        You are an expert business intelligence data extractor.
        Your task is to extract structured company information from the provided text.
        
        Extract the following fields perfectly into a JSON object matching this schema exactly:
        - legalName (string)
        - tradeName (string)
        - taxCode (string)
        - industries (array of strings)
        - businessModel (string)
        - products (array of objects with "name", "category", "description")
        - markets (array of strings)
        - targetCustomers (array of strings)
        - employeeTier (string)
        - website (string)
        - strengths (array of strings)
        - weaknesses (array of strings)
        - opportunities (array of strings)
        - threats (array of strings)
        - relationshipSuggestion (object with "suggestedType" string, "confidence" number between 0 and 1, "reasoning" array of strings)
        
        Relationship "suggestedType" MUST be exactly one of:
        - PARTNER_WITH
        - COMPETITOR_OF
        - SUPPLIER_OF
        - CUSTOMER_OF
        - POTENTIAL_PARTNER_OF
        Infer the relationshipSuggestion only from available business context.
        
        RULES:
        - Return ONLY a raw valid JSON object. 
        - DO NOT wrap the JSON in markdown code blocks. Start your response with { and end with }.
        - DO NOT output any conversational text or explanations.
        - Use null for unknown scalar values.
        - Use [] for unknown list values.
        - Do not invent taxCode, email, phone, or website if not present in the text.
        """;

    public GeminiExtractionProvider(ObjectMapper objectMapper,
                                    @Value("${app.ai.gemini.api-key:dummy-key}") String geminiApiKey,
                                    @Value("${app.ai.gemini.model:gemini-2.5-flash}") String geminiModel) {
        this.restClient = RestClient.builder().build();
        this.objectMapper = objectMapper;
        this.geminiApiKey = geminiApiKey;
        this.geminiModel = geminiModel;
    }

    @Override
    public ExtractedCompanyData extract(String sourceText) {
        String fullPrompt = EXTRACTION_SYSTEM_PROMPT + "\n\nText:\n" + sourceText;

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

                return objectMapper.readValue(rawAiOutput, ExtractedCompanyData.class);

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
