package com.apms.domain.assistant.controller;

import com.apms.common.response.ApiResponse;
import com.apms.domain.assistant.dto.AiChatRequest;
import com.apms.domain.assistant.dto.AiChatResponse;
import com.apms.domain.assistant.service.AiAssistantService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI Assistant controller.
 *
 * All endpoints require authentication.
 * Project-level row access is enforced inside AiAssistantService
 * via ProjectSecurityEvaluator.
 */
@RestController
@RequestMapping("/api/v1/ai-assistant")
@RequiredArgsConstructor
public class AiAssistantController {

    private final AiAssistantService aiAssistantService;

    /**
     * POST /api/v1/ai-assistant/chat
     *
     * Ask the AI assistant a question based on approved APMS data.
     * Requires an authenticated user who is a member of the specified project
     * (or a BUSINESS_OWNER, who has read access across all approved data).
     *
     * Request body:
     *   - projectId      (required) — project context
     *   - companyProfileId (optional) — scope answer to a specific company
     *   - question       (required) — the user's question
     *   - sessionId      (optional) — continue an existing session thread
     */
    @PostMapping("/chat")
    @PreAuthorize("hasAnyRole('BUSINESS_OWNER', 'BUSINESS_DEVELOPMENT_MANAGER', 'RESEARCH_STAFF')")
    public ResponseEntity<ApiResponse<AiChatResponse>> chat(
            @Valid @RequestBody AiChatRequest request) {

        AiChatResponse response = aiAssistantService.chat(request);
        return ResponseEntity.ok(ApiResponse.success(response, "Assistant response generated"));
    }
}
