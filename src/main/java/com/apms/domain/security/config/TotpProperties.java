package com.apms.domain.security.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@Configuration
@ConfigurationProperties(prefix = "security.totp")
public class TotpProperties {

    @NotBlank
    private String issuer = "APMS";

    @Min(6)
    @Max(8)
    private int digits = 6;

    @Min(30)
    private int periodSeconds = 30;

    @Min(0)
    private int allowedWindow = 1;

    @Min(1)
    private int enrollmentTtlMinutes = 10;

    @Min(1)
    private int maxFailedAttempts = 5;

    @Min(1)
    private int lockDurationMinutes = 10;

    @NotBlank
    private String encryptionKey;
}
