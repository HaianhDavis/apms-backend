package com.apms.domain.ai.service.provider;

import com.apms.domain.ai.dto.AiFieldResponse;
import com.apms.domain.ai.dto.ExtractedCompanyData;
import com.apms.domain.ai.dto.ExtractionFieldResult;
import com.apms.domain.ai.dto.RawExtractionOutput;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class AiExtractionResponseMapper {

    private final ObjectMapper objectMapper;

    public AiExtractionResponseMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public RawExtractionOutput mapResponse(String rawAiOutput) throws Exception {
        Map<String, AiFieldResponse> rawMap = new HashMap<>();
        Map<String, ExtractionFieldResult> fieldResults = new HashMap<>();

        try {
            // First try to parse it as the new nested schema
            rawMap = objectMapper.readValue(rawAiOutput, new TypeReference<Map<String, AiFieldResponse>>() {});

            // It might have succeeded but parsed it weirdly if it's actually flat JSON.
            // Check if it's the old schema by looking at a known field.
            // In the new schema, fields like 'legalName' should be an object with 'value', 'confidence'.
            // Jackson might have mapped a raw string to 'value' if we're not careful, but usually it throws an error.

            // If it parses into AiFieldResponse successfully and it has actual values
            ExtractedCompanyData extractedData = new ExtractedCompanyData();

            // Map to flat ExtractedCompanyData and fieldResults
            for (Map.Entry<String, AiFieldResponse> entry : rawMap.entrySet()) {
                String fieldName = entry.getKey();
                AiFieldResponse response = entry.getValue();

                Object val = response != null ? response.getValue() : null;

                if (val instanceof Map) {
                    Map<String, Object> nestedMap = (Map<String, Object>) val;
                    for (Map.Entry<String, Object> nestedEntry : nestedMap.entrySet()) {
                        String dottedName = fieldName + "." + nestedEntry.getKey();
                        ExtractionFieldResult fieldResult = ExtractionFieldResult.builder()
                                .fieldName(dottedName)
                                .value(nestedEntry.getValue())
                                .confidence(response != null ? response.getConfidence() : null)
                                .evidenceText(response != null ? response.getEvidenceText() : null)
                                .pageNumber(response != null ? response.getPageNumber() : null)
                                .build();
                        fieldResults.put(dottedName, fieldResult);
                    }
                } else {
                    ExtractionFieldResult fieldResult = ExtractionFieldResult.builder()
                            .fieldName(fieldName)
                            .value(val)
                            .confidence(response != null ? response.getConfidence() : null)
                            .evidenceText(response != null ? response.getEvidenceText() : null)
                            .pageNumber(response != null ? response.getPageNumber() : null)
                            .build();

                    fieldResults.put(fieldName, fieldResult);
                }
            }

            // Construct the ExtractedCompanyData dynamically using Jackson or manual mapping
            // An easy way is to build a flat map and then convertValue.
            // We need to re-assemble the nested maps for ExtractedCompanyData.
            Map<String, Object> flatMap = new HashMap<>();
            for (Map.Entry<String, AiFieldResponse> entry : rawMap.entrySet()) {
                AiFieldResponse response = entry.getValue();
                flatMap.put(entry.getKey(), response != null ? response.getValue() : null);
            }
            extractedData = objectMapper.convertValue(flatMap, ExtractedCompanyData.class);

            return RawExtractionOutput.builder()
                    .extractedData(extractedData)
                    .fieldResults(fieldResults)
                    .rawAiOutputString(rawAiOutput)
                    .build();

        } catch (Exception e) {
            log.warn("Failed to parse AI output as nested QA schema. Falling back to flat schema. Error: {}", e.getMessage());
            // Fallback: Try to parse as flat ExtractedCompanyData
            ExtractedCompanyData extractedData = objectMapper.readValue(rawAiOutput, ExtractedCompanyData.class);

            // Convert to a map to build fieldResults
            Map<String, Object> flatMap = objectMapper.convertValue(extractedData, new TypeReference<Map<String, Object>>() {});

            for (Map.Entry<String, Object> entry : flatMap.entrySet()) {
                Object val = entry.getValue();
                if (val instanceof Map) {
                    Map<String, Object> nestedMap = (Map<String, Object>) val;
                    for (Map.Entry<String, Object> nestedEntry : nestedMap.entrySet()) {
                        String dottedName = entry.getKey() + "." + nestedEntry.getKey();
                        ExtractionFieldResult fieldResult = ExtractionFieldResult.builder()
                                .fieldName(dottedName)
                                .value(nestedEntry.getValue())
                                .build();
                        fieldResults.put(dottedName, fieldResult);
                    }
                } else {
                    ExtractionFieldResult fieldResult = ExtractionFieldResult.builder()
                            .fieldName(entry.getKey())
                            .value(val)
                            .build();
                    fieldResults.put(entry.getKey(), fieldResult);
                }
            }

            return RawExtractionOutput.builder()
                    .extractedData(extractedData)
                    .fieldResults(fieldResults)
                    .rawAiOutputString(rawAiOutput)
                    .build();
        }
    }
}
