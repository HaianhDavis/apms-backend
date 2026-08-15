package com.apms.domain.security.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class StepUpChallengeResponse {
    private Long challengeId;
    private String maskedPhone;
    private Long expiresInSeconds;
    private String status;
}
