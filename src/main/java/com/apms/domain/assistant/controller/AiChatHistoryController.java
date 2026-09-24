package com.apms.domain.assistant.controller;

import com.apms.common.response.ApiResponse;
import com.apms.domain.assistant.dto.AiChatMessageResponse;
import com.apms.domain.assistant.dto.AiChatSessionResponse;
import com.apms.domain.assistant.service.AiChatHistoryService;
import com.apms.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AiChatHistoryController {

    private final AiChatHistoryService aiChatHistoryService;

    // ─────────────────────────────────────────────
    // PROJECT / COMPANY-DETAIL ASSISTANT HISTORY
    // ─────────────────────────────────────────────

    @GetMapping("/ai-assistant/sessions")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<AiChatSessionResponse>>> getProjectAssistantSessions(
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        List<AiChatSessionResponse> sessions = aiChatHistoryService.listProjectAssistantSessions(currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success(sessions));
    }

    @GetMapping("/ai-assistant/sessions/{sessionId}/messages")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<AiChatMessageResponse>>> getProjectAssistantMessages(
            @PathVariable String sessionId,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        List<AiChatMessageResponse> messages = aiChatHistoryService.getProjectAssistantMessages(currentUser.getId(), sessionId);
        return ResponseEntity.ok(ApiResponse.success(messages));
    }

    @DeleteMapping("/ai-assistant/sessions/{sessionId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Void>> deleteProjectAssistantSession(
            @PathVariable String sessionId,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        aiChatHistoryService.deleteProjectAssistantSession(currentUser.getId(), sessionId);
        return ResponseEntity.ok(ApiResponse.success(null, "Session deleted successfully"));
    }

    // ─────────────────────────────────────────────
    // OWNER ASSISTANT HISTORY
    // ─────────────────────────────────────────────

    @GetMapping("/owner/ai-assistant/sessions")
    @PreAuthorize("hasRole('BUSINESS_OWNER')")
    public ResponseEntity<ApiResponse<List<AiChatSessionResponse>>> getOwnerAssistantSessions(
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        List<AiChatSessionResponse> sessions = aiChatHistoryService.listOwnerAssistantSessions(currentUser.getId());
        return ResponseEntity.ok(ApiResponse.success(sessions));
    }

    @GetMapping("/owner/ai-assistant/sessions/{sessionId}/messages")
    @PreAuthorize("hasRole('BUSINESS_OWNER')")
    public ResponseEntity<ApiResponse<List<AiChatMessageResponse>>> getOwnerAssistantMessages(
            @PathVariable String sessionId,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        List<AiChatMessageResponse> messages = aiChatHistoryService.getOwnerAssistantMessages(currentUser.getId(), sessionId);
        return ResponseEntity.ok(ApiResponse.success(messages));
    }

    @DeleteMapping("/owner/ai-assistant/sessions/{sessionId}")
    @PreAuthorize("hasRole('BUSINESS_OWNER')")
    public ResponseEntity<ApiResponse<Void>> deleteOwnerAssistantSession(
            @PathVariable String sessionId,
            @AuthenticationPrincipal UserDetailsImpl currentUser) {

        aiChatHistoryService.deleteOwnerAssistantSession(currentUser.getId(), sessionId);
        return ResponseEntity.ok(ApiResponse.success(null, "Session deleted successfully"));
    }
}
