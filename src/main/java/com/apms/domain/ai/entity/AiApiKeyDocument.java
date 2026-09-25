package com.apms.domain.ai.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "ai_api_keys")
public class AiApiKeyDocument {

    @Id
    private String id;

    private String maskedKey;

    private String encryptedKey;

    private String encryptionIv;

    @Builder.Default
    private String keyVersion = "v1";

    private String label;

    @Builder.Default
    private String provider = "GEMINI";

    @Builder.Default
    private AiApiKeyStatus status = AiApiKeyStatus.ACTIVE;

    private Instant lastUsedAt;

    private Instant lastFailureAt;

    private Integer lastErrorCode;

    private String lastError;

    @Builder.Default
    private boolean active = true;

    private int orderIndex;

    private String createdSource;

    private Instant createdAt;

    private Instant updatedAt;
}
