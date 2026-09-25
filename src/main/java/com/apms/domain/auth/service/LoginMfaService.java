package com.apms.domain.auth.service;

import com.apms.common.exception.BusinessValidationException;
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
    private static final int MAX_FAILED_ATTEMPTS = 5;

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
                    .maxAttempts(MAX_FAILED_ATTEMPTS)
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
                    .maxAttempts(MAX_FAILED_ATTEMPTS)
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

    @Transactional(noRollbackFor = {BusinessValidationException.class, TotpException.class})
    public Account verifyLoginMfa(String challengeId, String totpCode) {
        // 1. Validate challenge ID format
        ChallengeComponents components = parseChallengeId(challengeId);
        if (components == null) {
            throw new BusinessValidationException("Verification session is no longer valid. Please sign in again.");
        }

        // 2. Lock and load challenge to protect against race conditions
        OtpChallenge challenge = challengeRepository.findByIdForUpdate(components.id())
                .orElseThrow(() -> new BusinessValidationException("Verification session is no longer valid. Please sign in again."));

        // 3. Cryptographically verify challenge secret
        if (challenge.getVerificationTicketHash() == null || !MessageDigest.isEqual(
                sha256(components.secret()).getBytes(StandardCharsets.UTF_8),
                challenge.getVerificationTicketHash().getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessValidationException("Verification session is no longer valid. Please sign in again.");
        }

        // 4. Verify challenge purpose
        if (challenge.getPurpose() != StepUpPurpose.LOGIN_MFA && challenge.getPurpose() != StepUpPurpose.LOGIN_MFA_ENROLLMENT) {
            throw new BusinessValidationException("Verification session is no longer valid. Please sign in again.");
        }

        // 5. Check if challenge was already consumed (single-use replay prevention)
        if (challenge.getUsedAt() != null || "CONSUMED".equalsIgnoreCase(challenge.getInvalidationReason())) {
            throw new BusinessValidationException("Verification session is no longer valid. Please sign in again.");
        }

        // 6. Check if challenge was locked or invalidated
        if (challenge.getInvalidatedAt() != null) {
            if ("LOCKED".equalsIgnoreCase(challenge.getInvalidationReason()) || challenge.getAttemptCount() >= challenge.getMaxAttempts()) {
                throw new BusinessValidationException("Too many incorrect verification attempts. Please sign in again.");
            }
            throw new BusinessValidationException("Verification session is no longer valid. Please sign in again.");
        }

        // 7. Check challenge expiration (do NOT extend or refresh expiration)
        if (challenge.getExpiresAt().isBefore(LocalDateTime.now())) {
            challenge.setInvalidatedAt(LocalDateTime.now());
            challenge.setInvalidationReason("EXPIRED");
            challengeRepository.save(challenge);
            throw new BusinessValidationException("Verification challenge has expired. Please sign in again.");
        }

        // 8. Check attempt limits before executing TOTP evaluation
        if (challenge.getAttemptCount() >= challenge.getMaxAttempts()) {
            challenge.setInvalidatedAt(LocalDateTime.now());
            challenge.setInvalidationReason("LOCKED");
            challengeRepository.save(challenge);
            throw new BusinessValidationException("Too many incorrect verification attempts. Please sign in again.");
        }

        Long accountId = challenge.getAccountId();

        // 9. Execute TOTP evaluation with atomic failure tracking and immediate lockout
        if (challenge.getPurpose() == StepUpPurpose.LOGIN_MFA) {
            try {
                totpVerificationService.verifyTotp(accountId, totpCode);
            } catch (TotpException e) {
                int newAttempts = challenge.getAttemptCount() + 1;
                challenge.setAttemptCount(newAttempts);
                if (newAttempts >= challenge.getMaxAttempts()) {
                    challenge.setInvalidatedAt(LocalDateTime.now());
                    challenge.setInvalidationReason("LOCKED");
                    challengeRepository.save(challenge);
                    log.warn("Login MFA challenge {} locked due to max attempts for accountId {}", challenge.getId(), accountId);
                    throw new BusinessValidationException("Too many incorrect verification attempts. Please sign in again.");
                }
                challengeRepository.save(challenge);
                log.warn("Login MFA failed attempt {}/{} on challenge {} for accountId {}", newAttempts, challenge.getMaxAttempts(), challenge.getId(), accountId);
                throw new BusinessValidationException("Incorrect verification code.");
            }
        } else {
            try {
                UUID enrollmentId = UUID.fromString(challenge.getOtpHash());
                totpEnrollmentService.confirmEnrollmentForLogin(accountId, enrollmentId, totpCode);
            } catch (TotpException e) {
                int newAttempts = challenge.getAttemptCount() + 1;
                challenge.setAttemptCount(newAttempts);
                if (newAttempts >= challenge.getMaxAttempts()) {
                    challenge.setInvalidatedAt(LocalDateTime.now());
                    challenge.setInvalidationReason("LOCKED");
                    challengeRepository.save(challenge);
                    log.warn("Login MFA enrollment challenge {} locked due to max attempts for accountId {}", challenge.getId(), accountId);
                    throw new BusinessValidationException("Too many incorrect verification attempts. Please sign in again.");
                }
                challengeRepository.save(challenge);
                log.warn("Login MFA enrollment failed attempt {}/{} on challenge {} for accountId {}", newAttempts, challenge.getMaxAttempts(), challenge.getId(), accountId);
                throw new BusinessValidationException("Incorrect verification code.");
            } catch (Exception e) {
                int newAttempts = challenge.getAttemptCount() + 1;
                challenge.setAttemptCount(newAttempts);
                if (newAttempts >= challenge.getMaxAttempts()) {
                    challenge.setInvalidatedAt(LocalDateTime.now());
                    challenge.setInvalidationReason("LOCKED");
                    challengeRepository.save(challenge);
                    log.warn("Login MFA enrollment challenge {} locked due to error for accountId {}", challenge.getId(), accountId);
                    throw new BusinessValidationException("Too many incorrect verification attempts. Please sign in again.");
                }
                challengeRepository.save(challenge);
                throw new BusinessValidationException("Incorrect verification code.");
            }
        }

        // 10. Atomically consume challenge upon successful verification (single-use)
        LocalDateTime now = LocalDateTime.now();
        challenge.setUsedAt(now);
        challenge.setInvalidatedAt(now);
        challenge.setInvalidationReason("CONSUMED");
        challengeRepository.save(challenge);

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessValidationException("Account not found."));

        if (Boolean.FALSE.equals(account.getIsActive())) {
            throw new DisabledException("Tài khoản đã bị vô hiệu hóa.");
        }

        log.info("Login MFA successfully verified for accountId {}, challenge {}", accountId, challenge.getId());
        return account;
    }

    @Transactional
    public void cancelLoginMfa(String challengeId) {
        ChallengeComponents components = parseChallengeId(challengeId);
        if (components == null) {
            return;
        }

        challengeRepository.findByIdForUpdate(components.id()).ifPresent(challenge -> {
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
