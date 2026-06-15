package com.apms.domain.auth.service;

import com.apms.common.exception.BusinessValidationException;
import com.apms.common.exception.ResourceNotFoundException;
import com.apms.domain.auth.RefreshToken;
import com.apms.domain.auth.repository.sql.RefreshTokenRepository;
import com.apms.domain.user.User;
import com.apms.domain.user.repository.sql.UserRepository;
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
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    // 7 days
    private final Long refreshTokenDurationMs = 604800000L;

    @Transactional
    public String createOrUpdateRefreshToken(Long userId) {
        String rawToken = userId + ":" + UUID.randomUUID().toString();
        String hashedToken = passwordEncoder.encode(rawToken);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        RefreshToken refreshToken = refreshTokenRepository.findByUser(user)
                .orElse(RefreshToken.builder()
                        .user(user)
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
        
        Long userId;
        try {
            userId = Long.parseLong(parts[0]);
        } catch (NumberFormatException e) {
            throw new BusinessValidationException("Invalid refresh token format");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessValidationException("User not found for token"));

        RefreshToken token = refreshTokenRepository.findByUser(user)
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
    public void revokeToken(Long userId) {
        User user = userRepository.findById(userId).orElseThrow();
        refreshTokenRepository.findByUser(user)
                .ifPresent(token -> {
                    token.setRevoked(true);
                    refreshTokenRepository.save(token);
                });
    }
}
