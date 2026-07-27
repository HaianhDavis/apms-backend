package com.apms.domain.ai.service.provider;

import com.apms.domain.ai.dto.RawExtractionOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AiExtractionResponseMapperTest {

    private AiExtractionResponseMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new AiExtractionResponseMapper(new ObjectMapper());
    }

    @Test
    void testMapResponse_NestedSchema() throws Exception {
        String json = """
            {
              "legalName": {
                "value": "Test Company LLC",
                "confidence": 0.95,
                "evidenceText": "According to Test Company LLC report"
              },
              "industries": {
                "value": ["Technology", "Software"],
                "confidence": 0.8
              }
            }
            """;

        RawExtractionOutput output = mapper.mapResponse(json);

        assertNotNull(output);
        assertNotNull(output.getExtractedData());
        assertEquals("Test Company LLC", output.getExtractedData().getLegalName());
        assertTrue(output.getExtractedData().getIndustries().contains("Technology"));

        assertNotNull(output.getFieldResults());
        assertTrue(output.getFieldResults().containsKey("legalName"));
        assertEquals("Test Company LLC", output.getFieldResults().get("legalName").getValue());
        assertEquals(0.95, output.getFieldResults().get("legalName").getConfidence());
    }

    @Test
    void testMapResponse_FlatSchemaFallback() throws Exception {
        String json = """
            {
              "legalName": "Old Flat Company",
              "industries": ["Finance"]
            }
            """;

        RawExtractionOutput output = mapper.mapResponse(json);

        assertNotNull(output);
        assertNotNull(output.getExtractedData());
        assertEquals("Old Flat Company", output.getExtractedData().getLegalName());

        assertNotNull(output.getFieldResults());
        assertTrue(output.getFieldResults().containsKey("legalName"));
        assertEquals("Old Flat Company", output.getFieldResults().get("legalName").getValue());
        assertNull(output.getFieldResults().get("legalName").getConfidence()); // Fallback won't have confidence
    }
}
