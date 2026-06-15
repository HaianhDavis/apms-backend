package com.apms.domain.ai.service;

import com.apms.common.enums.RelationshipType;
import com.apms.common.exception.ResourceNotFoundException;
import com.apms.domain.ai.dto.AiExtractionResult;
import com.apms.domain.ai.dto.ExtractedCompanyData;
import com.apms.domain.document.ImportJob;
import com.apms.domain.document.RawDocument;
import com.apms.domain.document.repository.mongo.RawDocumentRepository;
import com.apms.domain.document.repository.sql.ImportJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiExtractionService {

    private final ImportJobRepository importJobRepository;
    private final RawDocumentRepository rawDocumentRepository;

    @Value("${spring.ai.openai.api-key:dummy-key}")
    private String openAiApiKey;

    /**
     * AI prompt placeholder/template for future Spring AI implementation.
     */
    private static final String EXTRACTION_SYSTEM_PROMPT = """
        You are an expert business intelligence data extractor.
        Your task is to extract structured company information from the provided text.
        
        Extract the following fields:
        - companyName (string)
        - industry (string)
        - website (string)
        - description (string)
        
        Also suggest relationship types (PARTNER_WITH, COMPETITOR_OF, SUPPLIER_OF, CUSTOMER_OF, POTENTIAL_PARTNER_OF)
        based on the context, providing reasoning for each.
        
        Return ONLY valid JSON matching the required schema.
        """;

    @Transactional
    public AiExtractionResult extractCompanyData(Long importJobId) {
        // 1. Validate ImportJob exists
        ImportJob importJob = importJobRepository.findById(importJobId)
                .orElseThrow(() -> new ResourceNotFoundException("ImportJob not found with id: " + importJobId));

        // 2. Validate RawDocument exists
        String rawDocumentId = importJob.getRawDocumentId();
        if (!StringUtils.hasText(rawDocumentId)) {
            throw new ResourceNotFoundException("No RawDocument linked to ImportJob id: " + importJobId);
        }

        RawDocument rawDocument = rawDocumentRepository.findById(rawDocumentId)
                .orElseThrow(() -> new ResourceNotFoundException("RawDocument not found with id: " + rawDocumentId));

        // 3. Read extracted text or manual input content
        String sourceText = "";
        if (rawDocument.getSource() != null && StringUtils.hasText(rawDocument.getSource().getInputText())) {
            sourceText = rawDocument.getSource().getInputText();
        } else if (rawDocument.getProcessing() != null && StringUtils.hasText(rawDocument.getProcessing().getExtractedText())) {
            sourceText = rawDocument.getProcessing().getExtractedText();
        }

        log.info("Starting AI extraction for ImportJob {}, text length: {}", importJobId, sourceText.length());

        // 4. Determine if we should mock or call real AI
        // For the skeleton phase, or if the API key is a dummy value, return a mock result
        if ("dummy-key".equals(openAiApiKey) || openAiApiKey.isBlank()) {
            log.info("Mocking AI extraction (dummy API key detected)");
            return generateMockResult(importJobId, rawDocumentId, sourceText);
        }

        // TODO: Future implementation
        // ChatClient chatClient = ...
        // String response = chatClient.call(new Prompt(EXTRACTION_SYSTEM_PROMPT + "\n\nText:\n" + sourceText));
        // parse JSON response into ExtractedCompanyData...

        // Fallback to mock for now even if key is present to prevent accidental external calls during skeleton phase
        log.info("AI extraction skeleton active. Returning deterministic mock result.");
        return generateMockResult(importJobId, rawDocumentId, sourceText);
    }

    private AiExtractionResult generateMockResult(Long importJobId, String rawDocumentId, String sourceText) {
        ExtractedCompanyData.SuggestedRelationship suggestion = ExtractedCompanyData.SuggestedRelationship.builder()
                .relationshipType(RelationshipType.POTENTIAL_PARTNER_OF)
                .reasoning("Skeleton mock reasoning based on generic keywords.")
                .build();

        ExtractedCompanyData mockData = ExtractedCompanyData.builder()
                .companyName("Acme Corp (AI Extracted)")
                .industry("Technology")
                .website("https://acme.example.com")
                .description("A sample technology company extracted from document context.")
                .suggestedRelationships(List.of(suggestion))
                .build();

        return AiExtractionResult.builder()
                .importJobId(importJobId)
                .rawDocumentId(rawDocumentId)
                .extractedData(mockData)
                .rawAiOutput("{\n  \"status\": \"MOCK\",\n  \"message\": \"This is a deterministic mock AI extraction.\"\n}")
                .build();
    }
}
