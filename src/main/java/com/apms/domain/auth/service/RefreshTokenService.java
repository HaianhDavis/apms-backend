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
import java.util.List;

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

        // Keep separate sessions per device/tab. Replacing one account-wide
        // row caused an older browser tab to invalidate the active profile/chat session.
        RefreshToken refreshToken = RefreshToken.builder().account(account).build();

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

        List<RefreshToken> tokens = refreshTokenRepository.findAllByAccount(account);
        return tokens.stream()
                .filter(token -> !token.isRevoked())
                .filter(token -> token.getExpiryDate().compareTo(Instant.now()) >= 0)
                .filter(token -> passwordEncoder.matches(rawToken, token.getTokenHash()))
                .findFirst()
                .orElseThrow(() -> new BusinessValidationException("Refresh token mismatch or expired. Please sign in again"));
    }

    @Transactional
    public void revokeToken(Long accountId) {
        Account account = accountRepository.findById(accountId).orElseThrow();
        refreshTokenRepository.findAllByAccount(account).forEach(token -> token.setRevoked(true));
        refreshTokenRepository.flush();
    }
}
