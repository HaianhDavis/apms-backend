package com.apms.domain.ai.service.provider;

import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.ai.dto.RawExtractionOutput;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Slf4j
@Component
public class OpenAiExtractionProvider implements ExtractionProvider {

    private final ChatClient chatClient;
    private final AiExtractionResponseMapper responseMapper;
    private final String extractionSystemPrompt;

    public OpenAiExtractionProvider(ChatClient.Builder chatClientBuilder, AiExtractionResponseMapper responseMapper) {
        this.chatClient = chatClientBuilder.build();
        this.responseMapper = responseMapper;
        this.extractionSystemPrompt = loadPrompt();
    }

    private String loadPrompt() {
        try {
            ClassPathResource resource = new ClassPathResource("ai-prompts/company-extraction.prompt.md");
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to load OpenAI extraction prompt", e);
            throw new RuntimeException("Failed to load prompt", e);
        }
    }

    @Override
    public RawExtractionOutput extract(String sourceText) {
        String rawAiOutput = "";
        try {
            String userPrompt = "Text:\n" + sourceText;
            ChatResponse response = chatClient.prompt()
                    .system(extractionSystemPrompt)
                    .user(userPrompt)
                    .call()
                    .chatResponse();

            rawAiOutput = response.getResult().getOutput().getText();
            
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

            return responseMapper.mapResponse(rawAiOutput);

        } catch (Exception e) {
            log.error("OpenAI Extraction failed or parsing failed. Raw Output: {}", rawAiOutput, e);
            throw new BusinessValidationException("Failed to parse OpenAI extraction output. Invalid JSON or mismatch.");
        }
    }
}
