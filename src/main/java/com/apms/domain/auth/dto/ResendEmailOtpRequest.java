package com.apms.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ResendEmailOtpRequest {
    @NotBlank
    private String verificationTicket;
}
