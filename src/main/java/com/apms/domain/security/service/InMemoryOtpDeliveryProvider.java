package com.apms.domain.security.service;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Profile({"dev", "test"})
public class InMemoryOtpDeliveryProvider implements OtpDeliveryProvider {

    // Store OTP in memory for testing/development. Never exposed via HTTP.
    private final Map<String, String> otpStore = new ConcurrentHashMap<>();

    @Override
    public void sendOtp(String phoneNumber, String otp) {
        // Never log the OTP in development, production, or tests.
        otpStore.put(phoneNumber, otp);
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    /**
     * Package-private or test-only method to retrieve OTP during tests.
     */
    public String getOtpForTest(String phoneNumber) {
        return otpStore.get(phoneNumber);
    }

    public void clearOtpForTest(String phoneNumber) {
        otpStore.remove(phoneNumber);
    }
}
