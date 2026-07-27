package com.apms.domain.score.outbox;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.Min;

import java.time.Duration;

@Data
@Configuration
@ConfigurationProperties(prefix = "apms.outbox")
@Validated
public class RoleEvaluationOutboxProperties {

    private Duration pollDelay = Duration.ofSeconds(5);
    private Duration lockLease = Duration.ofMinutes(5);
    private int maxAttempts = 5;
    private Duration initialRetryDelay = Duration.ofSeconds(1);
    private Duration maximumRetryDelay = Duration.ofHours(1);
    @Min(1)
    private int batchSize = 1;

}
