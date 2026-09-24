package com.apms.config;

import com.apms.domain.ai.service.provider.AiExtractionResponseMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiExtractionConfig {

    @Bean
    @ConditionalOnMissingBean(AiExtractionResponseMapper.class)
    public AiExtractionResponseMapper aiExtractionResponseMapper(ObjectMapper objectMapper) {
        return new AiExtractionResponseMapper(objectMapper);
    }
}
