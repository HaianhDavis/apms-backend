package com.apms.domain.security.entity;

import com.apms.domain.security.enums.StepUpPurpose;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "otp_challenges")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OtpChallenge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private StepUpPurpose purpose;

    @Column(nullable = false)
    private String otpHash;

    @Column(length = 255)
    private String verificationTicketHash;

    private LocalDateTime ticketExpiresAt;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @Column(nullable = false)
    @Builder.Default
    private int attemptCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private int maxAttempts = 5;

    private LocalDateTime usedAt;
    
    private LocalDateTime invalidatedAt;
    
    private String invalidationReason;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @Column(length = 45)
    private String requestIp;
}
