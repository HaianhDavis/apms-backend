package com.apms.domain.ai.service;

import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.ai.dto.NetworkAiRecommendationDto;
import com.apms.domain.graph.dto.GraphCompanyDto;
import com.apms.domain.graph.service.GraphService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Slf4j
@Service
public class NetworkAiService {

    private final ChatClient chatClient;
    private final GraphService graphService;
    private final ObjectMapper objectMapper;
    private final String systemPrompt;

    public NetworkAiService(ChatClient.Builder chatClientBuilder, GraphService graphService, ObjectMapper objectMapper) {
        this.chatClient = chatClientBuilder.build();
        this.graphService = graphService;
        this.objectMapper = objectMapper;
        this.systemPrompt = loadPrompt();
    }

    private String loadPrompt() {
        try {
            ClassPathResource resource = new ClassPathResource("ai-prompts/network-recommendation.prompt.md");
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to load AI network recommendation prompt", e);
            throw new RuntimeException("Failed to load prompt", e);
        }
    }

    public NetworkAiRecommendationDto getRecommendationsForCompany(String companyId) {
        GraphCompanyDto targetCompany = graphService.getCompanyNodeWithRelationships(companyId);
        if (targetCompany == null) {
            throw new BusinessValidationException("Company not found in ecosystem network.");
        }

        // Build context for the AI
        StringBuilder context = new StringBuilder();
        context.append("Target Company Name: ").append(targetCompany.getName()).append("\n");
        context.append("Industry: ").append(targetCompany.getIndustry()).append("\n");
        if (targetCompany.getRelationships() != null) {
            context.append("Total Connections: ").append(targetCompany.getRelationships().size()).append("\n");
            context.append("Relationships:\n");
            targetCompany.getRelationships().forEach(rel -> {
                context.append("- ").append(rel.getRelationshipType())
                       .append(" with node ID ").append(rel.getTargetCompanyId()).append("\n");
            });
        }
        
        try {
            String aiResponse = chatClient.prompt()
                    .system(systemPrompt)
                    .user("Context:\n" + context.toString())
                    .call()
                    .content();

            // Clean markdown JSON wrapper if present
            if (aiResponse.startsWith("```json")) {
                aiResponse = aiResponse.replaceFirst("```json", "");
                if (aiResponse.endsWith("```")) {
                    aiResponse = aiResponse.substring(0, aiResponse.length() - 3);
                }
            } else if (aiResponse.startsWith("```")) {
                aiResponse = aiResponse.replaceFirst("```", "");
                if (aiResponse.endsWith("```")) {
                    aiResponse = aiResponse.substring(0, aiResponse.length() - 3);
                }
            }
            aiResponse = aiResponse.trim();

            return objectMapper.readValue(aiResponse, NetworkAiRecommendationDto.class);
        } catch (Exception e) {
            log.error("Failed to generate AI recommendations for company: {}", companyId, e);
            // Return fallback instead of throwing error to not break the UI
            return createFallbackRecommendation(targetCompany);
        }
    }

    private NetworkAiRecommendationDto createFallbackRecommendation(GraphCompanyDto company) {
        return NetworkAiRecommendationDto.builder()
                .highPriority(NetworkAiRecommendationDto.RecommendationItem.builder()
                        .title("Review Strategic Alignment")
                        .reason("Unable to fetch AI insights at this moment.")
                        .evidence("System fallback triggered.")
                        .action("Manually review the current status of " + company.getName())
                        .build())
                .mediumPriority(NetworkAiRecommendationDto.RecommendationItem.builder()
                        .title("Monitor Activity")
                        .reason("Routine check required.")
                        .evidence("Standard governance.")
                        .action("Keep track of recent news and project updates.")
                        .build())
                .opportunity(NetworkAiRecommendationDto.RecommendationItem.builder()
                        .title("Explore Synergies")
                        .reason("Potential value in ecosystem.")
                        .evidence("Based on shared industry: " + company.getIndustry())
                        .action("Evaluate joint opportunities when system is restored.")
                        .build())
                .build();
    }
}
