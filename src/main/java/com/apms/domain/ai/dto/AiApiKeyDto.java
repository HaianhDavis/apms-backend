package com.apms.domain.ai.dto;

import com.apms.domain.ai.entity.AiApiKeyStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiApiKeyDto {
    private String id;
    @Builder.Default
    private String provider = "GEMINI";
    private String label;
    private String maskedKey;
    private AiApiKeyStatus status;
    private Instant lastUsedAt;
    private Instant lastFailureAt;
    private Integer lastErrorCode;
    private String lastError;
    private boolean active;
    private int orderIndex;
    private String createdSource;
    private Instant createdAt;
    private Instant updatedAt;
}
