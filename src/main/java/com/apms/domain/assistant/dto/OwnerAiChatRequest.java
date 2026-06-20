package com.apms.domain.assistant.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class OwnerAiChatRequest {

    @NotBlank(message = "question must not be blank")
    private String question;

    /**
     * Optional: if provided, focus the assistant on this specific company profile.
     * The UI can pass this when the owner navigates to a company detail view.
     * No text matching is needed when this is provided.
     */
    private String companyProfileId;

    /** Optional: provide an existing sessionId to continue a conversation thread. */
    private String sessionId;
}
