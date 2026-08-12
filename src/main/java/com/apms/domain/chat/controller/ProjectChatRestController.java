package com.apms.domain.chat.controller;

import com.apms.common.response.ApiResponse;
import com.apms.domain.chat.dto.ChatMessageRequest;
import com.apms.domain.chat.dto.ChatMessageResponse;
import com.apms.domain.chat.service.ProjectChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/chat/messages")
@Tag(name = "Project Chat", description = "REST endpoints for managing project chat history and message edits")
@RequiredArgsConstructor
public class ProjectChatRestController {

    private final ProjectChatService chatService;

    @Operation(summary = "Get project chat history")
    @GetMapping
    public ResponseEntity<ApiResponse<Page<ChatMessageResponse>>> getHistory(
            @PathVariable Long projectId,
            @PageableDefault(size = 100, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        Page<ChatMessageResponse> history = chatService.getHistory(projectId, pageable);
        return ResponseEntity.ok(ApiResponse.success(history));
    }

    @Operation(summary = "Send a project chat message")
    @PostMapping
    public ResponseEntity<ApiResponse<ChatMessageResponse>> sendMessage(
            @PathVariable Long projectId,
            @Valid @RequestBody ChatMessageRequest request) {
        ChatMessageResponse response = chatService.sendMessage(projectId, request);
        return ResponseEntity.ok(ApiResponse.success(response, "Message sent"));
    }

    @Operation(summary = "Edit a sent message")
    @PatchMapping("/{messageId}")
    public ResponseEntity<ApiResponse<ChatMessageResponse>> editMessage(
            @PathVariable Long projectId,
            @PathVariable String messageId,
            @Valid @RequestBody ChatMessageRequest request) {
        ChatMessageResponse response = chatService.editMessage(projectId, messageId, request);
        return ResponseEntity.ok(ApiResponse.success(response, "Message updated"));
    }

    @Operation(summary = "Delete a sent message")
    @DeleteMapping("/{messageId}")
    public ResponseEntity<ApiResponse<Void>> deleteMessage(
            @PathVariable Long projectId,
            @PathVariable String messageId) {
        chatService.deleteMessage(projectId, messageId);
        return ResponseEntity.ok(ApiResponse.success(null, "Message deleted"));
    }
}
