package com.apms.domain.ai.service.provider;

import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.ai.dto.CriterionSuggestionOutput;
import com.apms.domain.score.dto.draft.CompetitorCriterionContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class GeminiCriterionSuggestionProvider implements CriterionSuggestionProvider {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String geminiApiKey;
    private final String geminiModel;

    private static final String GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1/models/{model}:generateContent?key={key}";

    public GeminiCriterionSuggestionProvider(ObjectMapper objectMapper,
                                             @Value("${app.ai.gemini.api-key:dummy-key}") String geminiApiKey,
                                             @Value("${app.ai.gemini.model:gemini-3.6-flash}") String geminiModel) {
        this.restClient = RestClient.builder().build();
        this.objectMapper = objectMapper;
        this.geminiApiKey = geminiApiKey;
        this.geminiModel = geminiModel != null && geminiModel.startsWith("models/") ? geminiModel.substring(7) : geminiModel;
    }

    @Override
    public CriterionSuggestionOutput generateCriterionSuggestion(CompetitorCriterionContext context, String promptTemplate) {
        String fullPrompt;
        try {
            fullPrompt = promptTemplate + "\n\nContext:\n" + objectMapper.writeValueAsString(context);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize context", e);
            throw new BusinessValidationException("Failed to serialize context");
        }

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

                JsonNode rootNode = objectMapper.readTree(responseBody);
                rawAiOutput = rootNode.path("candidates")
                        .get(0)
                        .path("content")
                        .path("parts")
                        .get(0)
                        .path("text")
                        .asText();

                rawAiOutput = cleanMarkdownFences(rawAiOutput);

                return parseAndValidate(rawAiOutput, context);

            } catch (RestClientResponseException e) {
                int statusCode = e.getStatusCode().value();
                if (statusCode == 429 || statusCode >= 500) {
                    log.warn("Gemini API rate limit or server error ({}). Attempt {} of {}", statusCode, attempt, maxRetries);
                    if (attempt >= maxRetries) {
                        log.error("Gemini API error after {} attempts.", maxRetries, e);
                        throw new BusinessValidationException(statusCode == 429 ? "GEMINI_RATE_LIMITED" : "GEMINI_SERVICE_UNAVAILABLE");
                    }
                    try {
                        Thread.sleep((long) Math.pow(2, attempt) * 1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new BusinessValidationException("Suggestion interrupted");
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
            } catch (BusinessValidationException e) {
                // Re-throw validation exceptions so they bubble up
                throw e;
            } catch (Exception e) {
                log.error("Gemini Generation failed or parsing failed. Raw Output: {}", rawAiOutput, e);
                throw new BusinessValidationException("Failed to parse Gemini suggestion output. Invalid JSON or mismatch.");
            }
        }
        throw new BusinessValidationException("Gemini generation failed.");
    }

    CriterionSuggestionOutput parseAndValidate(String json, CompetitorCriterionContext context) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(json);

        java.util.Set<String> allowedFields = java.util.Set.of(
            "criterionKey", "suggestedRawScore", "explanation", "confidence",
            "evidenceReferences", "missingData", "ambiguities"
        );

        java.util.Iterator<String> fieldNames = root.fieldNames();
        while (fieldNames.hasNext()) {
            String fieldName = fieldNames.next();
            if (!allowedFields.contains(fieldName)) {
                throw new BusinessValidationException("AI output contained forbidden or unknown field: " + fieldName);
            }
        }

        CriterionSuggestionOutput output = new CriterionSuggestionOutput();

        if (root.has("criterionKey") && !root.get("criterionKey").isNull()) {
            output.setCriterionKey(root.get("criterionKey").asText());
        }

        if (root.has("suggestedRawScore") && !root.get("suggestedRawScore").isNull()) {
            output.setSuggestedRawScore(new BigDecimal(root.get("suggestedRawScore").asText()));
        }

        if (root.has("explanation") && !root.get("explanation").isNull()) {
            output.setExplanation(root.get("explanation").asText());
        }

        if (root.has("confidence") && !root.get("confidence").isNull()) {
            output.setConfidence(new BigDecimal(root.get("confidence").asText()));
        }

        if (root.has("evidenceReferences") && root.get("evidenceReferences").isArray()) {
            List<CriterionSuggestionOutput.EvidenceReference> refs = objectMapper.convertValue(
                    root.get("evidenceReferences"),
                    new TypeReference<List<CriterionSuggestionOutput.EvidenceReference>>() {}
            );
            output.setEvidenceReferences(refs);
        }

        if (root.has("missingData") && root.get("missingData").isArray()) {
            List<String> missing = objectMapper.convertValue(root.get("missingData"), new TypeReference<List<String>>() {});
            output.setMissingData(missing);
        }

        if (root.has("ambiguities") && root.get("ambiguities").isArray()) {
            List<String> ambiguities = objectMapper.convertValue(root.get("ambiguities"), new TypeReference<List<String>>() {});
            output.setAmbiguities(ambiguities);
        }

        // Validate
        if (!context.getCriterionKey().equals(output.getCriterionKey())) {
            throw new BusinessValidationException("AI returned mismatching criterionKey: " + output.getCriterionKey());
        }

        if (output.getSuggestedRawScore() != null) {
            if (output.getExplanation() == null || output.getExplanation().trim().isEmpty()) {
                throw new BusinessValidationException("suggestedRawScore is present but explanation is missing");
            }
            BigDecimal score = output.getSuggestedRawScore();
            if (score.compareTo(BigDecimal.ZERO) < 0 || score.compareTo(new BigDecimal("100")) > 0) {
                throw new BusinessValidationException("Invalid suggestedRawScore: " + score);
            }
        } else {
            // Null score requires missing data
            if (output.getMissingData() == null || output.getMissingData().isEmpty()) {
                throw new BusinessValidationException("suggestedRawScore is null but missingData is empty");
            }
        }

        if (output.getConfidence() != null) {
            BigDecimal conf = output.getConfidence();
            if (conf.compareTo(BigDecimal.ZERO) < 0 || conf.compareTo(BigDecimal.ONE) > 0) {
                throw new BusinessValidationException("Invalid confidence: " + conf);
            }
        }

        if (output.getEvidenceReferences() != null && !output.getEvidenceReferences().isEmpty()) {
            java.util.Set<String> validIds = new java.util.HashSet<>();
            if (context.getDraftEvidence() != null) {
                context.getDraftEvidence().forEach(e -> {
                    if (e.get("evidenceId") != null) validIds.add(e.get("evidenceId").toString());
                });
            }
            if (context.getExternalSignals() != null) {
                context.getExternalSignals().forEach(e -> {
                    if (e.get("id") != null) validIds.add(e.get("id").toString());
                });
            }

            for (CriterionSuggestionOutput.EvidenceReference ref : output.getEvidenceReferences()) {
                boolean isValidId = ref.getEvidenceId() != null && validIds.contains(ref.getEvidenceId());
                boolean isValidSourceField = ref.getSourceFieldPath() != null &&
                    ((context.getReferenceFacts() != null && context.getReferenceFacts().containsKey(ref.getSourceFieldPath())) ||
                     (context.getTargetFacts() != null && context.getTargetFacts().containsKey(ref.getSourceFieldPath())));

                if (!isValidId && !isValidSourceField) {
                    throw new BusinessValidationException("Unknown evidence reference: " +
                        (ref.getEvidenceId() != null ? ref.getEvidenceId() : ref.getSourceFieldPath()));
                }
            }
        }

        return output;
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
