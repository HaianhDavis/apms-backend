package com.apms.domain.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginMfaChallengeResponse {
    private boolean mfaRequired;
    private boolean mfaEnrollmentRequired;
    private String challengeId;
    @Builder.Default
    private String method = "TOTP";
    private String qrCodeDataUrl;
    private String manualEntryKey;
}
