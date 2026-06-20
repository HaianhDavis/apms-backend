package com.apms.domain.assistant.service;

import com.apms.domain.assistant.dto.AssistantContext;

/**
 * Contract for AI assistant answer generation.
 * Different implementations: Gemini, Mock.
 */
public interface AssistantProvider {

    /**
     * Generates a business-focused answer based only on the approved APMS context.
     *
     * @param question user's question
     * @param context  assembled approved context (profile, graph, score)
     * @return the AI-generated answer string
     */
    String answer(String question, AssistantContext context);
}
