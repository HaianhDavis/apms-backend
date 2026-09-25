package com.apms.domain.auth.service;

public record EmailDeliveryResult(boolean delivered, String status, String reason) {
    public static EmailDeliveryResult smtpSuccess() {
        return new EmailDeliveryResult(true, "SMTP_SUCCESS", "Verification code sent");
    }

    public static EmailDeliveryResult failed(String reason) {
        return new EmailDeliveryResult(false, "SMTP_FAILURE", reason);
    }

    public static EmailDeliveryResult devFallback() {
        return new EmailDeliveryResult(false, "DEV_FALLBACK", "Development fallback: verification code is shown in the server logs");
    }
}
