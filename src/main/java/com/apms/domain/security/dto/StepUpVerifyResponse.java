package com.apms.domain.security.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class StepUpVerifyResponse {
    private String stepUpToken;
    private Long expiresInSeconds;
    private LocalDateTime expiresAt;
    private boolean secureAccessGranted;
}
