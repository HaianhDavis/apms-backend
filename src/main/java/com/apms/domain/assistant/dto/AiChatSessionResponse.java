package com.apms.domain.assistant.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class AiChatSessionResponse {
    private String sessionId;
    private String firstQuestion;
    private String lastQuestion;
    private String lastAnswerPreview;
    private int messageCount;
    private LocalDateTime startedAt;
    private LocalDateTime lastMessageAt;
    private Long projectId;
    private String companyProfileId;
}
