package com.apms.domain.auth.service;

import com.apms.common.exception.BusinessValidationException;
import com.apms.common.exception.ResourceNotFoundException;
import com.apms.domain.auth.RefreshToken;
import com.apms.domain.auth.repository.sql.RefreshTokenRepository;
import com.apms.domain.user.Account;
import com.apms.domain.user.repository.sql.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final AccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;

    // 7 days
    private final Long refreshTokenDurationMs = 604800000L;

    @Transactional
    public String createOrUpdateRefreshToken(Long accountId) {
        String rawToken = accountId + ":" + UUID.randomUUID().toString();
        String hashedToken = passwordEncoder.encode(rawToken);

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));

        RefreshToken refreshToken = refreshTokenRepository.findByAccount(account)
                .orElse(RefreshToken.builder()
                        .account(account)
                        .build());

        refreshToken.setTokenHash(hashedToken);
        refreshToken.setExpiryDate(Instant.now().plusMillis(refreshTokenDurationMs));
        refreshToken.setRevoked(false);

        refreshTokenRepository.save(refreshToken);
        return rawToken;
    }

    public RefreshToken findAndVerifyToken(String rawToken) {
        String[] parts = rawToken.split(":");
        if (parts.length != 2) {
            throw new BusinessValidationException("Invalid refresh token format");
        }
        
        Long accountId;
        try {
            accountId = Long.parseLong(parts[0]);
        } catch (NumberFormatException e) {
            throw new BusinessValidationException("Invalid refresh token format");
        }

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessValidationException("Account not found for token"));

        RefreshToken token = refreshTokenRepository.findByAccount(account)
                .orElseThrow(() -> new BusinessValidationException("Refresh token not found"));

        if (token.isRevoked()) {
            throw new BusinessValidationException("Refresh token is revoked");
        }

        if (!passwordEncoder.matches(rawToken, token.getTokenHash())) {
            throw new BusinessValidationException("Refresh token mismatch");
        }

        if (token.getExpiryDate().compareTo(Instant.now()) < 0) {
            refreshTokenRepository.delete(token);
            throw new BusinessValidationException("Refresh token was expired. Please make a new login request");
        }

        return token;
    }

    @Transactional
    public void revokeToken(Long accountId) {
        Account account = accountRepository.findById(accountId).orElseThrow();
        refreshTokenRepository.findByAccount(account)
                .ifPresent(token -> {
                    token.setRevoked(true);
                    refreshTokenRepository.save(token);
                });
    }
}
