package com.apms.domain.security.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class StepUpChallengeRequest {
    @NotBlank(message = "Purpose is required")
    private String purpose;
}
