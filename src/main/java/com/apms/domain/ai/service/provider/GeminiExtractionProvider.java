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
                                    @Value("${app.ai.gemini.model:gemini-3.6-flash}") String geminiModel) {
        this.restClient = RestClient.builder().build();
        this.objectMapper = objectMapper;
        this.responseMapper = responseMapper;
        this.geminiApiKey = geminiApiKey;
        this.geminiModel = geminiModel != null && geminiModel.startsWith("models/") ? geminiModel.substring(7) : geminiModel;
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
                ),
                "generationConfig", Map.of(
                        "responseMimeType", "application/json",
                        "responseSchema", buildResponseSchema()
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
                int statusCode = e.getStatusCode().value();
                if (statusCode == 429 || statusCode >= 500) {
                    log.warn("Gemini API rate limit or server error ({}). Attempt {} of {}", statusCode, attempt, maxRetries);
                    if (attempt >= maxRetries) {
                        log.error("Gemini API error after {} attempts.", maxRetries, e);
                        throw new BusinessValidationException(statusCode == 429 ? "GEMINI_RATE_LIMITED" : "GEMINI_SERVICE_UNAVAILABLE");
                    }
                    try {
                        Thread.sleep((long) Math.pow(2, attempt) * 1000); // exponential backoff
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new BusinessValidationException("Extraction interrupted");
                    }
                } else if (statusCode == 404) {
                    log.error("Gemini model unavailable (404): {}", e.getResponseBodyAsString());
                    throw new BusinessValidationException("GEMINI_MODEL_UNAVAILABLE");
                } else if (statusCode == 400) {
                    log.error("Gemini invalid request (400): {}", e.getResponseBodyAsString());
                    throw new BusinessValidationException("GEMINI_INVALID_REQUEST");
                } else if (statusCode == 401 || statusCode == 403) {
                    log.error("Gemini authentication failed ({}): {}", statusCode, e.getResponseBodyAsString());
                    throw new BusinessValidationException("GEMINI_AUTHENTICATION_FAILED");
                } else {
                    log.error("Gemini API call failed: {}", e.getResponseBodyAsString(), e);
                    throw new BusinessValidationException("Gemini API call failed: " + statusCode);
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                log.warn("Gemini extraction parsing failed. Attempt {} of {}. Error: {}", attempt, maxRetries, e.getOriginalMessage());
                if (attempt < maxRetries) {
                    continue;
                }
                log.error("Gemini JSON parse failed after {} attempt(s).", maxRetries, e);
                throw new BusinessValidationException("GEMINI_JSON_PARSE_FAILED");
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("GEMINI_JSON_PARSE_FAILED")) {
                    if (attempt < maxRetries) {
                        continue;
                    }
                    throw new BusinessValidationException("GEMINI_JSON_PARSE_FAILED");
                }
                log.warn("Gemini extraction failed. Attempt {} of {}.", attempt, maxRetries, e);
                if (attempt < maxRetries) {
                    continue;
                }
                throw new BusinessValidationException("Failed to parse Gemini extraction output. Invalid JSON or mismatch.");
            }
        }
        throw new BusinessValidationException("Gemini extraction failed.");
    }

    private Map<String, Object> buildResponseSchema() {
        Map<String, Object> stringFieldSchema = Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "value", Map.of("type", "STRING"),
                        "confidence", Map.of("type", "NUMBER"),
                        "evidenceText", Map.of("type", "STRING"),
                        "pageNumber", Map.of("type", "INTEGER"),
                        "sourceDocumentIds", Map.of("type", "ARRAY", "items", Map.of("type", "STRING"))
                ),
                "required", List.of("value", "confidence", "evidenceText", "sourceDocumentIds")
        );

        Map<String, Object> stringListFieldSchema = Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "value", Map.of("type", "ARRAY", "items", Map.of("type", "STRING")),
                        "confidence", Map.of("type", "NUMBER"),
                        "evidenceText", Map.of("type", "STRING"),
                        "pageNumber", Map.of("type", "INTEGER"),
                        "sourceDocumentIds", Map.of("type", "ARRAY", "items", Map.of("type", "STRING"))
                ),
                "required", List.of("value", "confidence", "evidenceText", "sourceDocumentIds")
        );

        Map<String, Object> productSchema = Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "value", Map.of(
                                "type", "ARRAY",
                                "items", Map.of(
                                        "type", "OBJECT",
                                        "properties", Map.of(
                                                "name", Map.of("type", "STRING"),
                                                "category", Map.of("type", "STRING"),
                                                "description", Map.of("type", "STRING")
                                        )
                                )
                        ),
                        "confidence", Map.of("type", "NUMBER"),
                        "evidenceText", Map.of("type", "STRING"),
                        "pageNumber", Map.of("type", "INTEGER"),
                        "sourceDocumentIds", Map.of("type", "ARRAY", "items", Map.of("type", "STRING"))
                ),
                "required", List.of("value", "confidence", "evidenceText", "sourceDocumentIds")
        );

        Map<String, Object> integerFieldSchema = Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "value", Map.of("type", "INTEGER"),
                        "confidence", Map.of("type", "NUMBER"),
                        "evidenceText", Map.of("type", "STRING"),
                        "pageNumber", Map.of("type", "INTEGER"),
                        "sourceDocumentIds", Map.of("type", "ARRAY", "items", Map.of("type", "STRING"))
                ),
                "required", List.of("value", "confidence", "evidenceText", "sourceDocumentIds")
        );

        Map<String, Object> fallbackObjectSchema = Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "value", Map.of("type", "OBJECT"),
                        "confidence", Map.of("type", "NUMBER"),
                        "evidenceText", Map.of("type", "STRING"),
                        "pageNumber", Map.of("type", "INTEGER"),
                        "sourceDocumentIds", Map.of("type", "ARRAY", "items", Map.of("type", "STRING"))
                ),
                "required", List.of("value", "confidence", "evidenceText", "sourceDocumentIds")
        );

        Map<String, Object> properties = new java.util.HashMap<>();
        
        List<String> stringFields = List.of(
                "legalName", "tradeName", "taxCode", "businessModel", "employeeTier", 
                "revenueTier", "website", "address", "companySize"
        );
        for (String field : stringFields) {
            properties.put(field, stringFieldSchema);
        }

        List<String> stringListFields = List.of(
                "industries", "markets", "targetCustomers", "email", "phone",
                "strengths", "weaknesses", "opportunities", "threats"
        );
        for (String field : stringListFields) {
            properties.put(field, stringListFieldSchema);
        }

        properties.put("products", productSchema);
        properties.put("employeeCount", integerFieldSchema);
        
        List<String> objectFields = List.of("financial", "market", "innovation", "risk", "compliance");
        for (String field : objectFields) {
            properties.put(field, fallbackObjectSchema);
        }

        List<String> allRequiredFields = new java.util.ArrayList<>();
        allRequiredFields.addAll(stringFields);
        allRequiredFields.addAll(stringListFields);
        allRequiredFields.add("products");
        allRequiredFields.add("employeeCount");
        allRequiredFields.addAll(objectFields);

        return Map.of(
                "type", "OBJECT",
                "properties", properties,
                "required", allRequiredFields
        );
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

    private String rawOutputExcerpt(String rawOutput) {
        if (rawOutput == null) {
            return "";
        }
        String normalized = rawOutput.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 1000 ? normalized : normalized.substring(0, 1000) + "...";
    }
}
