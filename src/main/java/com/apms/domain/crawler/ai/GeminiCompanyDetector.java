package com.apms.domain.crawler.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.apms.domain.crawler.domain.CompanyMatch;
import com.apms.domain.crawler.domain.TrackedCompany;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Calls the Gemini API for semantic company detection.
 * Follows the same patterns as the backend's GeminiExtractionProvider:
 * - REST call to generativelanguage.googleapis.com
 * - Exponential backoff on 429 rate limits
 * - Markdown fence stripping
 * - Structured JSON response parsing
 */
@Slf4j
@Component
public class GeminiCompanyDetector {

    private static final String GEMINI_API_URL =
            "https://generativelanguage.googleapis.com/v1/models/{model}:generateContent?key={key}";

    private static final int MAX_RETRIES = 3;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final CompanyDetectionPrompt promptBuilder;

    @Value("${crawler.ai.gemini.api-key:dummy-key}")
    private String geminiApiKey;

    @Value("${crawler.ai.gemini.model:gemini-2.5-flash}")
    private String geminiModel;

    public GeminiCompanyDetector(RestClient restClient,
                                 ObjectMapper objectMapper,
                                 CompanyDetectionPrompt promptBuilder) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.promptBuilder = promptBuilder;
    }

    /**
     * Detect tracked companies in the given article using Gemini AI.
     *
     * @param title             article title
     * @param content           article content (summary + body text)
     * @param trackedCompanies  current list of tracked companies
     * @return detection result with matches and confidence scores
     */
    public CompanyDetectionResult detect(String title, String content,
                                          List<TrackedCompany> trackedCompanies) {
        // Check if running in mock mode (no real API key)
        if (isMockMode()) {
            log.debug("GeminiCompanyDetector: Running in mock mode (no API key configured)");
            return CompanyDetectionResult.builder()
                    .relevant(false)
                    .rawAiOutput("{\"mock\": true}")
                    .processingTimeMs(0)
                    .build();
        }

        String prompt = promptBuilder.buildPrompt(title, content, trackedCompanies);
        long startTime = System.currentTimeMillis();

        Map<String, Object> requestPayload = Map.of(
                "contents", List.of(
                        Map.of("parts", List.of(
                                Map.of("text", prompt)
                        ))
                )
        );

        String rawAiOutput = "";
        int attempt = 0;

        while (attempt < MAX_RETRIES) {
            try {
                attempt++;

                String responseBody = restClient.post()
                        .uri(GEMINI_API_URL, geminiModel, geminiApiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(requestPayload)
                        .retrieve()
                        .body(String.class);

                // Parse Gemini response structure
                JsonNode rootNode = objectMapper.readTree(responseBody);
                rawAiOutput = rootNode.path("candidates")
                        .get(0)
                        .path("content")
                        .path("parts")
                        .get(0)
                        .path("text")
                        .asText();

                rawAiOutput = cleanMarkdownFences(rawAiOutput);

                long elapsed = System.currentTimeMillis() - startTime;
                return parseDetectionResponse(rawAiOutput, trackedCompanies, elapsed);

            } catch (RestClientResponseException e) {
                if (e.getStatusCode().value() == 429) {
                    log.warn("GeminiCompanyDetector: Rate limit (429), attempt {} of {}", attempt, MAX_RETRIES);
                    if (attempt >= MAX_RETRIES) {
                        long elapsed = System.currentTimeMillis() - startTime;
                        return CompanyDetectionResult.builder()
                                .relevant(false)
                                .rawAiOutput(rawAiOutput)
                                .processingTimeMs(elapsed)
                                .errorMessage("Gemini API rate limit exceeded after " + MAX_RETRIES + " attempts")
                                .build();
                    }
                    sleepWithBackoff(attempt);
                } else {
                    log.error("GeminiCompanyDetector: API call failed ({}): {}",
                            e.getStatusCode(), e.getResponseBodyAsString());
                    long elapsed = System.currentTimeMillis() - startTime;
                    return CompanyDetectionResult.builder()
                            .relevant(false)
                            .rawAiOutput(e.getResponseBodyAsString())
                            .processingTimeMs(elapsed)
                            .errorMessage("Gemini API error: " + e.getStatusCode())
                            .build();
                }
            } catch (Exception e) {
                log.error("GeminiCompanyDetector: Processing failed. Raw output: {}", rawAiOutput, e);
                long elapsed = System.currentTimeMillis() - startTime;
                return CompanyDetectionResult.builder()
                        .relevant(false)
                        .rawAiOutput(rawAiOutput)
                        .processingTimeMs(elapsed)
                        .errorMessage("Failed to parse AI response: " + e.getMessage())
                        .build();
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        return CompanyDetectionResult.builder()
                .relevant(false)
                .processingTimeMs(elapsed)
                .errorMessage("Gemini detection failed after " + MAX_RETRIES + " attempts")
                .build();
    }

    /**
     * Parse the AI's JSON response into a CompanyDetectionResult.
     */
    private CompanyDetectionResult parseDetectionResponse(String jsonOutput,
                                                           List<TrackedCompany> trackedCompanies,
                                                           long elapsed) {
        try {
            JsonNode root = objectMapper.readTree(jsonOutput);
            boolean isRelevant = root.path("isRelevant").asBoolean(false);

            List<CompanyMatch> matches = new ArrayList<>();
            JsonNode matchesNode = root.path("matches");

            if (matchesNode.isArray()) {
                for (JsonNode matchNode : matchesNode) {
                    String companyName = matchNode.path("companyName").asText("");
                    double confidence = matchNode.path("confidence").asDouble(0.0);
                    String reason = matchNode.path("reason").asText("");

                    // Only include matches with confidence >= 0.5
                    if (confidence < 0.5 || companyName.isBlank()) {
                        continue;
                    }

                    // Resolve company ID from tracked companies
                    String companyId = trackedCompanies.stream()
                            .filter(tc -> tc.getCompanyName().equalsIgnoreCase(companyName))
                            .map(TrackedCompany::getId)
                            .findFirst()
                            .orElse(null);

                    matches.add(CompanyMatch.builder()
                            .companyId(companyId)
                            .companyName(companyName)
                            .confidenceScore(confidence)
                            .matchReason(reason)
                            .matchType("SEMANTIC")
                            .build());
                }
            }

            return CompanyDetectionResult.builder()
                    .matches(matches)
                    .relevant(isRelevant && !matches.isEmpty())
                    .rawAiOutput(jsonOutput)
                    .processingTimeMs(elapsed)
                    .build();

        } catch (Exception e) {
            log.error("GeminiCompanyDetector: Failed to parse JSON response: {}", jsonOutput, e);
            return CompanyDetectionResult.builder()
                    .relevant(false)
                    .rawAiOutput(jsonOutput)
                    .processingTimeMs(elapsed)
                    .errorMessage("JSON parsing failed: " + e.getMessage())
                    .build();
        }
    }

    /**
     * Check if running in mock mode (no real Gemini API key).
     */
    private boolean isMockMode() {
        return "dummy-key".equals(geminiApiKey) ||
               geminiApiKey == null ||
               geminiApiKey.isBlank();
    }

    /**
     * Remove markdown code fences from AI response.
     */
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

    /**
     * Exponential backoff sleep for rate limiting.
     */
    private void sleepWithBackoff(int attempt) {
        try {
            long sleepMs = (long) Math.pow(2, attempt) * 1000;
            log.info("GeminiCompanyDetector: Sleeping {}ms before retry", sleepMs);
            Thread.sleep(sleepMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
