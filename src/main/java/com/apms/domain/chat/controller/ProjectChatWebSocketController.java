package com.apms.domain.chat.controller;

import com.apms.domain.chat.dto.ChatMessageRequest;
import com.apms.domain.chat.dto.ChatMessageResponse;
import com.apms.domain.chat.service.ProjectChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

@Controller
@RequiredArgsConstructor
public class ProjectChatWebSocketController {

    private final ProjectChatService chatService;

    @MessageMapping("/projects/{projectId}/chat.send")
    public void sendMessage(
            @DestinationVariable Long projectId,
            @Valid @Payload ChatMessageRequest request) {
        // Validation of project membership and message persistence
        // are handled inside the service method.
        chatService.sendMessage(projectId, request);
    }
}
