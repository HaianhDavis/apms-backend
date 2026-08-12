package com.apms.domain.security.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

public class TotpDto {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TotpStatusResponse {
        private boolean enrolled;
        private boolean enabled;
        private boolean locked;
        private LocalDateTime lockedUntil;
        private boolean secureAccessActive;
        private LocalDateTime secureAccessExpiresAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TotpEnrollmentStartResponse {
        private UUID enrollmentId;
        private String qrCodeDataUrl;
        private String manualEntryKey;
        private String issuer;
        private String accountName;
        private LocalDateTime expiresAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TotpEnrollmentConfirmRequest {
        private UUID enrollmentId;
        private String code;
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StepUpStatusResponse {
        private boolean required;
        private boolean verified;
        private LocalDateTime expiresAt;
        private boolean secureAccessActive;
        private boolean totpConfigured;
        private String scope;
        private String resourceId;
    }
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TotpStepUpVerifyRequest {
        private String code;
        private String scope;
        private String resourceId;
    }
}
