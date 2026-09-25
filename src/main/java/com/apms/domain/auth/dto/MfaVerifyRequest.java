package com.apms.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MfaVerifyRequest {
    @NotBlank(message = "Challenge ID is required")
    private String challengeId;

    @NotBlank(message = "TOTP code is required")
    @Pattern(regexp = "^[0-9]{6}$", message = "TOTP code must be exactly 6 digits")
    private String totpCode;
}
