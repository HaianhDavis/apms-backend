package com.apms.domain.auth;

import com.apms.domain.user.Account;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "refresh_tokens")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefreshToken {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "account_id", referencedColumnName = "id")
    private Account account;

    @Column(nullable = false, unique = true)
    private String tokenHash;

    @Column(nullable = false)
    private Instant expiryDate;

    @Builder.Default
    private boolean isRevoked = false;
}
