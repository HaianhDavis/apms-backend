package com.apms.domain.auth.service;

import com.apms.domain.auth.dto.LoginMfaChallengeResponse;
import com.apms.domain.security.dto.TotpDto.TotpEnrollmentStartResponse;
import com.apms.domain.security.entity.AccountTotpCredential;
import com.apms.domain.security.entity.OtpChallenge;
import com.apms.domain.security.enums.StepUpPurpose;
import com.apms.domain.security.exception.TotpException;
import com.apms.domain.security.repository.AccountTotpCredentialRepository;
import com.apms.domain.security.repository.OtpChallengeRepository;
import com.apms.domain.security.service.TotpEnrollmentService;
import com.apms.domain.security.service.TotpVerificationService;
import com.apms.domain.user.Account;
import com.apms.domain.user.repository.sql.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class LoginMfaService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int CHALLENGE_LIFETIME_SECONDS = 300; // 5 minutes

    private final OtpChallengeRepository challengeRepository;
    private final AccountRepository accountRepository;
    private final AccountTotpCredentialRepository credentialRepository;
    private final TotpEnrollmentService totpEnrollmentService;
    private final TotpVerificationService totpVerificationService;

    @Transactional
    public LoginMfaChallengeResponse createLoginChallenge(Long accountId, String email) {
        // 1. Invalidate any existing active login MFA challenges for this account (superseded)
        invalidateActiveChallengesForAccount(accountId);

        AccountTotpCredential credential = credentialRepository.findByAccountId(accountId).orElse(null);
        boolean isEnrolled = credential != null && credential.isEnabled();

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusSeconds(CHALLENGE_LIFETIME_SECONDS);
        String ticketSecret = generateRandomSecret();
        String ticketHash = sha256(ticketSecret);

        if (isEnrolled) {
            OtpChallenge challenge = OtpChallenge.builder()
                    .accountId(accountId)
                    .purpose(StepUpPurpose.LOGIN_MFA)
                    .otpHash("TOTP")
                    .verificationTicketHash(ticketHash)
                    .expiresAt(expiresAt)
                    .ticketExpiresAt(expiresAt)
                    .maxAttempts(5)
                    .attemptCount(0)
                    .build();
            challenge = challengeRepository.save(challenge);

            String challengeId = challenge.getId() + "_" + ticketSecret;
            return LoginMfaChallengeResponse.builder()
                    .mfaRequired(true)
                    .mfaEnrollmentRequired(false)
                    .challengeId(challengeId)
                    .method("TOTP")
                    .build();
        } else {
            // Unenrolled account: start enrollment and bind challenge to the exact enrollmentId
            TotpEnrollmentStartResponse startResponse = totpEnrollmentService.startEnrollment(accountId, email);

            OtpChallenge challenge = OtpChallenge.builder()
                    .accountId(accountId)
                    .purpose(StepUpPurpose.LOGIN_MFA_ENROLLMENT)
                    .otpHash(startResponse.getEnrollmentId().toString()) // Server-bound to exact enrollmentId
                    .verificationTicketHash(ticketHash)
                    .expiresAt(expiresAt)
                    .ticketExpiresAt(expiresAt)
                    .maxAttempts(5)
                    .attemptCount(0)
                    .build();
            challenge = challengeRepository.save(challenge);

            String challengeId = challenge.getId() + "_" + ticketSecret;
            return LoginMfaChallengeResponse.builder()
                    .mfaRequired(false)
                    .mfaEnrollmentRequired(true)
                    .challengeId(challengeId)
                    .method("TOTP")
                    .qrCodeDataUrl(startResponse.getQrCodeDataUrl())
                    .manualEntryKey(startResponse.getManualEntryKey())
                    .build();
        }
    }

    @Transactional
    public Account verifyLoginMfa(String challengeId, String totpCode) {
        ChallengeComponents components = parseChallengeId(challengeId);
        if (components == null) {
            throw new BadCredentialsException("Invalid or expired verification code.");
        }

        OtpChallenge challenge = challengeRepository.findById(components.id())
                .orElseThrow(() -> new BadCredentialsException("Invalid or expired verification code."));

        if (!MessageDigest.isEqual(
                sha256(components.secret()).getBytes(StandardCharsets.UTF_8),
                challenge.getVerificationTicketHash().getBytes(StandardCharsets.UTF_8))) {
            throw new BadCredentialsException("Invalid or expired verification code.");
        }

        if (challenge.getUsedAt() != null || challenge.getInvalidatedAt() != null) {
            throw new BadCredentialsException("Verification code is no longer valid.");
        }

        if (challenge.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BadCredentialsException("Verification code has expired.");
        }

        if (challenge.getAttemptCount() >= challenge.getMaxAttempts()) {
            throw new BadCredentialsException("Too many verification attempts.");
        }

        Long accountId = challenge.getAccountId();

        if (challenge.getPurpose() == StepUpPurpose.LOGIN_MFA) {
            try {
                totpVerificationService.verifyTotp(accountId, totpCode);
            } catch (TotpException e) {
                challenge.setAttemptCount(challenge.getAttemptCount() + 1);
                challengeRepository.save(challenge);
                throw new BadCredentialsException("Invalid or expired verification code.");
            }
        } else if (challenge.getPurpose() == StepUpPurpose.LOGIN_MFA_ENROLLMENT) {
            try {
                UUID enrollmentId = UUID.fromString(challenge.getOtpHash());
                totpEnrollmentService.confirmEnrollmentForLogin(accountId, enrollmentId, totpCode);
            } catch (Exception e) {
                challenge.setAttemptCount(challenge.getAttemptCount() + 1);
                challengeRepository.save(challenge);
                throw new BadCredentialsException("Invalid or expired verification code.");
            }
        } else {
            throw new BadCredentialsException("Invalid challenge purpose.");
        }

        // Consume challenge upon successful verification (single-use)
        LocalDateTime now = LocalDateTime.now();
        challenge.setUsedAt(now);
        challenge.setInvalidatedAt(now);
        challenge.setInvalidationReason("CONSUMED");
        challengeRepository.save(challenge);

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BadCredentialsException("Account not found."));

        if (Boolean.FALSE.equals(account.getIsActive())) {
            throw new DisabledException("Tài khoản đã bị vô hiệu hóa.");
        }

        return account;
    }

    @Transactional
    public void cancelLoginMfa(String challengeId) {
        ChallengeComponents components = parseChallengeId(challengeId);
        if (components == null) {
            return;
        }

        challengeRepository.findById(components.id()).ifPresent(challenge -> {
            if (challenge.getVerificationTicketHash() != null && MessageDigest.isEqual(
                    sha256(components.secret()).getBytes(StandardCharsets.UTF_8),
                    challenge.getVerificationTicketHash().getBytes(StandardCharsets.UTF_8))) {
                if (challenge.getUsedAt() == null && challenge.getInvalidatedAt() == null) {
                    challenge.setInvalidatedAt(LocalDateTime.now());
                    challenge.setInvalidationReason("CANCELLED_BY_USER");
                    challengeRepository.save(challenge);
                }
            }
        });
    }

    private void invalidateActiveChallengesForAccount(Long accountId) {
        LocalDateTime now = LocalDateTime.now();
        List<OtpChallenge> activeChallenges = challengeRepository
                .findByAccountIdAndPurposeAndUsedAtIsNullAndInvalidatedAtIsNull(accountId, StepUpPurpose.LOGIN_MFA);
        for (OtpChallenge c : activeChallenges) {
            c.setInvalidatedAt(now);
            c.setInvalidationReason("SUPERSEDED");
            challengeRepository.save(c);
        }

        List<OtpChallenge> activeEnrollments = challengeRepository
                .findByAccountIdAndPurposeAndUsedAtIsNullAndInvalidatedAtIsNull(accountId, StepUpPurpose.LOGIN_MFA_ENROLLMENT);
        for (OtpChallenge c : activeEnrollments) {
            c.setInvalidatedAt(now);
            c.setInvalidationReason("SUPERSEDED");
            challengeRepository.save(c);
        }
    }

    private ChallengeComponents parseChallengeId(String challengeId) {
        if (challengeId == null || challengeId.isBlank()) {
            return null;
        }
        int separatorIndex = challengeId.indexOf('_');
        if (separatorIndex <= 0 || separatorIndex == challengeId.length() - 1) {
            return null;
        }
        try {
            Long id = Long.parseLong(challengeId.substring(0, separatorIndex));
            String secret = challengeId.substring(separatorIndex + 1);
            return new ChallengeComponents(id, secret);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private record ChallengeComponents(Long id, String secret) {}

    private String generateRandomSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not found", e);
        }
    }
}
