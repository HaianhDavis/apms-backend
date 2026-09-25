package com.apms.domain.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AddAiApiKeyRequest {

    @NotBlank(message = "API key is required")
    private String apiKey;

    private String label;

    public void setKey(String key) {
        if (this.apiKey == null || this.apiKey.isBlank()) {
            this.apiKey = key;
        }
    }
}
