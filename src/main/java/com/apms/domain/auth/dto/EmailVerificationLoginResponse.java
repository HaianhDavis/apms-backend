package com.apms.domain.auth.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class EmailVerificationLoginResponse {
    boolean requiresEmailVerification;
    String verificationTicket;
    String email;
    boolean emailDelivered;
    String emailDeliveryMessage;
}
