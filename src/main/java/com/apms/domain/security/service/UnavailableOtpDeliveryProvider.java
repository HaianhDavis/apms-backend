package com.apms.domain.security.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnMissingBean(OtpDeliveryProvider.class)
public class UnavailableOtpDeliveryProvider implements OtpDeliveryProvider {

    @Override
    public void sendOtp(String phoneNumber, String otp) {
        // Do nothing. This provider should not be called because isAvailable is false.
    }

    @Override
    public boolean isAvailable() {
        return false;
    }
}
