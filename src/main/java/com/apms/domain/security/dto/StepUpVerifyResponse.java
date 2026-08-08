package com.apms.domain.security.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class StepUpVerifyResponse {
    private String stepUpToken;
    private Long expiresInSeconds;
}
