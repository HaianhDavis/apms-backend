package com.apms.domain.security.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class StepUpVerifyRequest {
    @NotBlank(message = "OTP is required")
    @Pattern(regexp = "^\\d{6}$", message = "OTP must be 6 digits")
    private String otp;
}
