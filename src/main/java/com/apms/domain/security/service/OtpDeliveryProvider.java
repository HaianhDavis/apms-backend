package com.apms.domain.security.service;

public interface OtpDeliveryProvider {
    void sendOtp(String phoneNumber, String otp);
    boolean isAvailable();
}
