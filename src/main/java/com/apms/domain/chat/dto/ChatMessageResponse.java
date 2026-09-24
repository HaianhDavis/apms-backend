package com.apms.domain.chat.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ChatMessageResponse {
    private String id;
    private Long projectId;
    private Long senderId;
    private String senderName;
    private String content;
    private String replyToMessageId;
    private Boolean isEdited;
    private Boolean isDeleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // e.g., "MESSAGE_CREATED", "MESSAGE_UPDATED", "MESSAGE_DELETED"
    private String eventType;
}
