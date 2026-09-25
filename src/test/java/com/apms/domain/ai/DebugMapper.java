package com.apms.domain.ai;

import com.apms.domain.ai.service.provider.AiExtractionResponseMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.apms.domain.ai.dto.RawExtractionOutput;

public class DebugMapper {
    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AiExtractionResponseMapper responseMapper = new AiExtractionResponseMapper(mapper);

        String mockGeminiOutput = """
        {
          "legalName": { "value": "Samsung", "confidence": 0.9, "evidenceText": "Samsung Electronics", "sourceDocumentIds": ["doc-1"] },
          "industries": { "value": ["Consumer Electronics", "Semiconductors"], "confidence": 0.95, "evidenceText": "electronics", "sourceDocumentIds": ["doc-1"] },
          "businessModel": { "value": "B2B and B2C", "confidence": 0.8, "evidenceText": "B2B", "sourceDocumentIds": ["doc-1"] },
          "strengths": { "value": ["Strong R&D"], "confidence": 0.9, "evidenceText": "R&D", "sourceDocumentIds": ["doc-1"] }
        }
        """;

        RawExtractionOutput output = responseMapper.mapResponse(mockGeminiOutput);
        System.out.println("====== MAPPER OUTPUT ======");
        System.out.println("ExtractedData: " + mapper.writeValueAsString(output.getExtractedData()));
        System.out.println("FieldResults keys: " + output.getFieldResults().keySet());
        if (output.getFieldResults().containsKey("industries")) {
            System.out.println("Industries class: " + output.getFieldResults().get("industries").getValue().getClass().getName());
            System.out.println("Industries value: " + output.getFieldResults().get("industries").getValue());
        }
        if (output.getFieldResults().containsKey("businessModel")) {
            System.out.println("BusinessModel value: " + output.getFieldResults().get("businessModel").getValue());
        }
        if (output.getFieldResults().containsKey("strengths")) {
            System.out.println("Strengths value: " + output.getFieldResults().get("strengths").getValue());
        }
        System.out.println("===========================");
    }
}
