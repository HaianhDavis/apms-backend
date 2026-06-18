package com.apms.domain.ai.service.provider;

import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.ai.dto.ExtractedCompanyData;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class OpenAiExtractionProvider implements ExtractionProvider {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

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
        - Return valid JSON ONLY.
        - NO markdown formatting (no ```json or ```).
        - NO explanations.
        - Use null for unknown scalar values.
        - Use [] for unknown list values.
        - Do not invent taxCode, email, phone, or website if not present in the text.
        """;

    public OpenAiExtractionProvider(ChatClient.Builder chatClientBuilder, ObjectMapper objectMapper) {
        this.chatClient = chatClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    @Override
    public ExtractedCompanyData extract(String sourceText) {
        String rawAiOutput = "";
        try {
            String userPrompt = "Text:\n" + sourceText;
            ChatResponse response = chatClient.prompt()
                    .system(EXTRACTION_SYSTEM_PROMPT)
                    .user(userPrompt)
                    .call()
                    .chatResponse();

            rawAiOutput = response.getResult().getOutput().getText();
            
            // Clean up possible markdown wrappers if the AI disobeys
            if (rawAiOutput.startsWith("```json")) {
                rawAiOutput = rawAiOutput.replaceFirst("```json", "");
                if (rawAiOutput.endsWith("```")) {
                    rawAiOutput = rawAiOutput.substring(0, rawAiOutput.length() - 3);
                }
            } else if (rawAiOutput.startsWith("```")) {
                rawAiOutput = rawAiOutput.replaceFirst("```", "");
                if (rawAiOutput.endsWith("```")) {
                    rawAiOutput = rawAiOutput.substring(0, rawAiOutput.length() - 3);
                }
            }
            rawAiOutput = rawAiOutput.trim();

            return objectMapper.readValue(rawAiOutput, ExtractedCompanyData.class);

        } catch (Exception e) {
            log.error("OpenAI Extraction failed or parsing failed. Raw Output: {}", rawAiOutput, e);
            throw new BusinessValidationException("Failed to parse OpenAI extraction output. Invalid JSON or mismatch.");
        }
    }
}
