package com.apms.domain.security.service;

import com.apms.domain.security.enums.StepUpPurpose;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

@Slf4j
@Service
public class StepUpTokenService {

    private final String stepUpSecret;
    private final long expirationSeconds;

    public StepUpTokenService(
            @Value("${jwt.step-up.secret:${jwt.secret}}") String stepUpSecret,
            @Value("${apms.stepup.token.expiration-seconds:600}") long expirationSeconds) {
        this.stepUpSecret = stepUpSecret;
        this.expirationSeconds = expirationSeconds;
    }

    public boolean isConfigured() {
        return StringUtils.hasText(stepUpSecret) && stepUpSecret.length() >= 32;
    }

    public String generateToken(Long accountId, StepUpPurpose purpose) {
        if (!isConfigured()) {
            throw new IllegalStateException("Step-up token secret is not configured or too short");
        }

        SecretKey key = Keys.hmacShaKeyFor(stepUpSecret.getBytes(StandardCharsets.UTF_8));
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + (expirationSeconds * 1000));

        return Jwts.builder()
                .subject(String.valueOf(accountId))
                .claim("tokenType", "STEP_UP")
                .claim("purpose", purpose.name())
                .claim("authMethod", "SMS_OTP")
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(key)
                .compact();
    }

    public String generateTokenForScope(Long accountId, String scope, String resourceId, long customExpirationSeconds) {
        if (!isConfigured()) {
            throw new IllegalStateException("Step-up token secret is not configured or too short");
        }

        SecretKey key = Keys.hmacShaKeyFor(stepUpSecret.getBytes(StandardCharsets.UTF_8));
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + (customExpirationSeconds * 1000));

        return Jwts.builder()
                .subject(String.valueOf(accountId))
                .claim("tokenType", "STEP_UP")
                .claim("scope", scope)
                .claim("resourceId", resourceId)
                .claim("authMethod", "TOTP")
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(key)
                .compact();
    }

    public boolean validateToken(String token, Long expectedAccountId, StepUpPurpose expectedPurpose) {
        if (!isConfigured()) {
            return false;
        }

        try {
            SecretKey key = Keys.hmacShaKeyFor(stepUpSecret.getBytes(StandardCharsets.UTF_8));
            
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String tokenSubject = claims.getSubject();
            if (tokenSubject == null || !tokenSubject.equals(String.valueOf(expectedAccountId))) {
                return false;
            }

            String tokenType = claims.get("tokenType", String.class);
            if (!"STEP_UP".equals(tokenType)) {
                return false;
            }

            String purpose = claims.get("purpose", String.class);
            if (purpose == null || !purpose.equals(expectedPurpose.name())) {
                return false;
            }

            return true;
        } catch (Exception ex) {
            log.warn("Invalid step-up token: {}", ex.getMessage());
            return false;
        }
    }

    public boolean validateTokenForScope(String token, Long expectedAccountId, String expectedScope, String expectedResourceId) {
        if (!isConfigured()) {
            return false;
        }

        try {
            SecretKey key = Keys.hmacShaKeyFor(stepUpSecret.getBytes(StandardCharsets.UTF_8));
            
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String tokenSubject = claims.getSubject();
            if (tokenSubject == null || !tokenSubject.equals(String.valueOf(expectedAccountId))) {
                return false;
            }

            String tokenType = claims.get("tokenType", String.class);
            if (!"STEP_UP".equals(tokenType)) {
                return false;
            }

            String scope = claims.get("scope", String.class);
            if (scope == null || !scope.equals(expectedScope)) {
                return false;
            }

            String resourceId = claims.get("resourceId", String.class);
            if (resourceId == null || !resourceId.equals(expectedResourceId)) {
                return false;
            }

            String authMethod = claims.get("authMethod", String.class);
            if (!"TOTP".equals(authMethod)) {
                return false;
            }

            return true;
        } catch (Exception ex) {
            log.warn("Invalid step-up token for scope: {}", ex.getMessage());
            return false;
        }
    }
}
