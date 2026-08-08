package com.apms.domain.security.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "account_totp_credentials")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountTotpCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long accountId;

    @Column(nullable = false)
    private byte[] encryptedSecret;

    @Column(nullable = false, length = 32)
    private byte[] encryptionIv;

    @Column(length = 30)
    private String encryptionKeyVersion;

    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = false;

    private UUID enrollmentId;

    private LocalDateTime enrollmentExpiresAt;

    private LocalDateTime verifiedAt;

    private Long lastAcceptedTimeStep;

    @Column(nullable = false)
    @Builder.Default
    private int failedAttempts = 0;

    private LocalDateTime lockedUntil;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    @Version
    @Column(nullable = false)
    @Builder.Default
    private Long version = 0L;
}
