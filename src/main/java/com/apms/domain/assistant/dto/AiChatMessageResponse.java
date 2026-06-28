package com.apms.domain.assistant.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class AiChatMessageResponse {
    private String id;
    private String sessionId;
    private Long userId;
    private Long projectId;
    private String companyProfileId;
    private String question;
    private String answer;
    private List<String> sources;
    private List<String> suggestedActions;
    private LocalDateTime createdAt;
}
