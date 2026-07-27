package com.apms.domain.notification.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RegisterFcmTokenRequest {
    @NotBlank(message = "token is required")
    private String token;

    private String deviceType = "WEB";
}
