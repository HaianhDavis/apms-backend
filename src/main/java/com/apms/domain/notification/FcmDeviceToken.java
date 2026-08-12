package com.apms.domain.notification;

import com.apms.domain.user.Account;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "fcm_device_tokens", indexes = {
        @Index(name = "idx_fcm_tokens_account_active", columnList = "account_id,is_active")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FcmDeviceToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Account account;

    @Column(nullable = false, length = 2048)
    private String token;

    @Column(length = 40)
    private String deviceType;

    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    private LocalDateTime lastSeenAt;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
