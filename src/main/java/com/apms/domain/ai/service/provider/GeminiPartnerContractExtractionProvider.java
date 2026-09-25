package com.apms.domain.ai.service.provider;

import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.contract.entity.PartnerContractExtractionDraft.ContractExtractionFieldResult;
import com.apms.domain.contract.dto.PartnerContractExtractionOutput;
import com.apms.domain.contract.entity.PartnerContractExtractionDraft.ClauseCandidate;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class GeminiPartnerContractExtractionProvider implements PartnerContractExtractionProvider {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final GeminiApiKeyManager geminiApiKeyManager;
    private final String geminiModel;

    private static final String GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1/models/{model}:generateContent?key={key}";

    public GeminiPartnerContractExtractionProvider(ObjectMapper objectMapper,
                                                   GeminiApiKeyManager geminiApiKeyManager,
                                                   @Value("${app.ai.gemini.model:gemini-3.8-flash}") String geminiModel) {
        this.restClient = RestClient.builder()
                .requestInterceptor(GeminiCredentialDiagnostics.interceptor("PartnerContractExtraction")).build();
        this.objectMapper = objectMapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        this.geminiApiKeyManager = geminiApiKeyManager;
        this.geminiModel = geminiModel;
    }

    @Override
    public PartnerContractExtractionOutput extractContract(String sourceText) {
        String prompt = "Extract contract metadata and clauses from the provided text.\n" +
                "Output STRICTLY in the allowed JSON schema. Do not include overallScore, criterionScore, or companyRole.\n" +
                "Context: \n" + sourceText;

        Map<String, Object> requestPayload = Map.of(
                "contents", List.of(
                        Map.of("parts", List.of(
                                Map.of("text", prompt)
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
                        .uri(GEMINI_API_URL, geminiModel, geminiApiKeyManager.getApiKey())
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

                return parseAndValidate(rawAiOutput);

            } catch (RestClientResponseException e) {
                if (e.getStatusCode().value() == 429) {
                    log.warn("Gemini API rate limit exceeded (429). Attempt {} of {}", attempt, maxRetries);
                    if (attempt >= maxRetries) {
                        throw new BusinessValidationException("Gemini AI rate limit exceeded.");
                    }
                    try {
                        Thread.sleep((long) Math.pow(2, attempt) * 1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new BusinessValidationException("Extraction interrupted");
                    }
                } else {
                    log.error("Gemini API call failed: {}", e.getResponseBodyAsString(), e);
                    throw new BusinessValidationException("Gemini API call failed: " + e.getStatusCode());
                }
            } catch (BusinessValidationException e) {
                throw e;
            } catch (Exception e) {
                log.error("Gemini Extraction failed or parsing failed. Raw Output: {}", rawAiOutput, e);
                throw new BusinessValidationException("Failed to parse Gemini output. Invalid JSON.");
            }
        }
        throw new BusinessValidationException("Gemini generation failed.");
    }

    private PartnerContractExtractionOutput parseAndValidate(String json) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(json);

        Set<String> allowedTopLevelFields = Set.of(
                "metadataFields", "clauseCandidates", "warnings", "missingData", "ambiguities"
        );
        Set<String> forbiddenFields = Set.of(
                "overallScore", "criterionScore", "normalizedScore", "weightedScore",
                "AHPWeight", "companyRole", "relationshipType", "reviewStatus", "approvalStatus",
                "managerDecision", "currentVersion", "lifecycleStatus", "approvedBy", "approvedAt"
        );

        validateNode(root, allowedTopLevelFields, forbiddenFields);

        PartnerContractExtractionOutput output = new PartnerContractExtractionOutput();
        output.setRawAiOutput(json);

        if (root.has("metadataFields") && root.get("metadataFields").isArray()) {
            List<ContractExtractionFieldResult> fields = objectMapper.convertValue(
                    root.get("metadataFields"),
                    new TypeReference<List<ContractExtractionFieldResult>>() {}
            );
            output.setMetadataFields(fields);
        }

        if (root.has("clauseCandidates") && root.get("clauseCandidates").isArray()) {
            List<ClauseCandidate> clauses = objectMapper.convertValue(
                    root.get("clauseCandidates"),
                    new TypeReference<List<ClauseCandidate>>() {}
            );
            output.setClauseCandidates(clauses);
        }

        if (root.has("warnings") && root.get("warnings").isArray()) {
            output.setWarnings(objectMapper.convertValue(root.get("warnings"), new TypeReference<List<String>>() {}));
        }

        if (root.has("missingData") && root.get("missingData").isArray()) {
            output.setMissingData(objectMapper.convertValue(root.get("missingData"), new TypeReference<List<String>>() {}));
        }

        if (root.has("ambiguities") && root.get("ambiguities").isArray()) {
            output.setAmbiguities(objectMapper.convertValue(root.get("ambiguities"), new TypeReference<List<String>>() {}));
        }

        return output;
    }

    private void validateNode(JsonNode root, Set<String> allowedFields, Set<String> forbiddenFields) {
        Iterator<String> fieldNames = root.fieldNames();
        while (fieldNames.hasNext()) {
            String fieldName = fieldNames.next();
            if (forbiddenFields.contains(fieldName)) {
                throw new BusinessValidationException("AI output contained forbidden field: " + fieldName);
            }
            if (allowedFields != null && !allowedFields.contains(fieldName)) {
                throw new BusinessValidationException("AI output contained unknown field: " + fieldName);
            }
        }
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
