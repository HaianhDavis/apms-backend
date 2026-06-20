package com.apms.domain.assistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AiChatRequest {

    @NotNull(message = "projectId is required")
    private Long projectId;

    /** Optional: MongoDB CompanyProfile.id to scope the question to a specific company. */
    private String companyProfileId;

    @NotBlank(message = "question must not be blank")
    private String question;

    /** Optional: provide an existing sessionId to continue a conversation thread. */
    private String sessionId;
}
