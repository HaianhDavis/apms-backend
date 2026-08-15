package com.apms.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class EmailOtpRequest {
    @NotBlank
    private String verificationTicket;

    @NotBlank
    @Pattern(regexp = "\\d{6}")
    private String otp;
}
