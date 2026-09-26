package com.apms.domain.security.service;

import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.security.config.TotpProperties;
import com.apms.domain.security.dto.StepUpVerifyResponse;
import com.apms.domain.security.dto.TotpDto.TotpEnrollmentStartResponse;
import com.apms.domain.security.dto.TotpDto.TotpStatusResponse;
import com.apms.domain.security.entity.AccountTotpCredential;
import com.apms.domain.security.entity.OtpChallenge;
import com.apms.domain.security.enums.StepUpPurpose;
import com.apms.domain.security.exception.TotpException;
import com.apms.domain.security.repository.AccountTotpCredentialRepository;
import com.apms.domain.security.repository.OtpChallengeRepository;
import dev.samstevens.totp.exceptions.QrGenerationException;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.qr.QrGenerator;
import dev.samstevens.totp.qr.ZxingPngQrGenerator;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static dev.samstevens.totp.util.Utils.getDataUriForImage;

@Service
@RequiredArgsConstructor
public class TotpEnrollmentService {

    private final AccountTotpCredentialRepository repository;
    private final OtpChallengeRepository challengeRepository;
    private final TotpSecretEncryptionService encryptionService;
    private final TotpVerificationService verificationService;
    private final TotpProperties totpProperties;
    private final Clock clock;
    private final StepUpAuthenticationService stepUpAuthenticationService;

    private final SecretGenerator secretGenerator = new DefaultSecretGenerator(64);
    private final QrGenerator qrGenerator = new ZxingPngQrGenerator();

    @Transactional(readOnly = true)
    public TotpStatusResponse getStatus(Long accountId) {
        Optional<AccountTotpCredential> optional = repository.findByAccountId(accountId);
        if (optional.isEmpty()) {
            return TotpStatusResponse.builder().enrolled(false).enabled(false).locked(false).build();
        }
        AccountTotpCredential cred = optional.get();
        boolean locked = cred.getLockedUntil() != null && cred.getLockedUntil().isAfter(LocalDateTime.now(clock));
        return TotpStatusResponse.builder()
                .enrolled(cred.isEnabled())
                .enabled(cred.isEnabled())
                .locked(locked)
                .lockedUntil(locked ? cred.getLockedUntil() : null)
                .secureAccessActive(cred.getOwnerSecureSessionExpiresAt() != null
                        && cred.getOwnerSecureSessionExpiresAt().isAfter(LocalDateTime.now(clock)))
                .secureAccessExpiresAt(cred.getOwnerSecureSessionExpiresAt())
                .build();
    }

    @Transactional
    public TotpEnrollmentStartResponse startEnrollment(Long accountId, String accountEmail) {
        Optional<AccountTotpCredential> optional = repository.findByAccountIdWithLock(accountId);
        
        AccountTotpCredential cred;
        if (optional.isPresent()) {
            cred = optional.get();
            if (cred.isEnabled()) {
                throw new TotpException("TOTP_ALREADY_ENROLLED");
            }
            if (cred.getEnrollmentExpiresAt() != null && cred.getEnrollmentExpiresAt().isAfter(LocalDateTime.now(clock))) {
                // There is a valid pending enrollment. We can either return error or overwrite.
                // Let's overwrite to allow the user to retry easily if they closed the modal without canceling.
            }
        } else {
            cred = AccountTotpCredential.builder()
                    .accountId(accountId)
                    .build();
        }

        String plainSecret = secretGenerator.generate();
        TotpSecretEncryptionService.EncryptedSecret encrypted = encryptionService.encrypt(plainSecret, accountId);

        cred.setEncryptedSecret(encrypted.cipherText());
        cred.setEncryptionIv(encrypted.iv());
        cred.setEncryptionKeyVersion(encrypted.keyVersion());
        cred.setEnabled(false);
        cred.setEnrollmentId(UUID.randomUUID());
        cred.setEnrollmentExpiresAt(LocalDateTime.now(clock).plusMinutes(totpProperties.getEnrollmentTtlMinutes()));
        cred.setFailedAttempts(0);
        cred.setLockedUntil(null);
        cred.setLastAcceptedTimeStep(null);

        repository.save(cred);

        String accountName = accountEmail != null ? accountEmail : "user_" + accountId;
        QrData data = new QrData.Builder()
                .label(accountName)
                .secret(plainSecret)
                .issuer(totpProperties.getIssuer())
                .algorithm(dev.samstevens.totp.code.HashingAlgorithm.SHA1)
                .digits(totpProperties.getDigits())
                .period(totpProperties.getPeriodSeconds())
                .build();

        try {
            byte[] qrImage = qrGenerator.generate(data);
            String qrDataUri = getDataUriForImage(qrImage, qrGenerator.getImageMimeType());
            
            return TotpEnrollmentStartResponse.builder()
                    .enrollmentId(cred.getEnrollmentId())
                    .qrCodeDataUrl(qrDataUri)
                    .manualEntryKey(plainSecret)
                    .issuer(totpProperties.getIssuer())
                    .accountName(accountName)
                    .expiresAt(cred.getEnrollmentExpiresAt())
                    .build();
        } catch (QrGenerationException e) {
            throw new TotpException("Failed to generate QR code", e);
        }
    }

    @Transactional
    public StepUpVerifyResponse confirmEnrollment(Long accountId, UUID enrollmentId, String code) {
        AccountTotpCredential cred = repository.findByAccountIdWithLock(accountId)
                .orElseThrow(() -> new TotpException("TOTP_ENROLLMENT_NOT_FOUND"));

        if (cred.isEnabled()) {
            throw new TotpException("TOTP_ALREADY_ENROLLED");
        }
        
        if (cred.getEnrollmentId() == null || !cred.getEnrollmentId().equals(enrollmentId)) {
            throw new TotpException("TOTP_ENROLLMENT_NOT_FOUND");
        }

        if (cred.getEnrollmentExpiresAt() != null && cred.getEnrollmentExpiresAt().isBefore(LocalDateTime.now(clock))) {
            throw new TotpException("TOTP_ENROLLMENT_EXPIRED");
        }

        if (cred.getLockedUntil() != null && cred.getLockedUntil().isAfter(LocalDateTime.now(clock))) {
            throw new TotpException("TOTP_ACCOUNT_LOCKED");
        }

        String secret = encryptionService.decrypt(
                cred.getEncryptedSecret(),
                cred.getEncryptionIv(),
                cred.getEncryptionKeyVersion(),
                accountId
        );

        long matchedTimeStep;
        try {
            matchedTimeStep = verificationService.verifyAndGetTimeStep(secret, code);
        } catch (TotpException e) {
            int fails = cred.getFailedAttempts() + 1;
            cred.setFailedAttempts(fails);
            if (fails >= totpProperties.getMaxFailedAttempts()) {
                cred.setLockedUntil(LocalDateTime.now(clock).plusMinutes(totpProperties.getLockDurationMinutes()));
            }
            repository.save(cred);
            throw e;
        }

        // Success
        cred.setEnabled(true);
        cred.setEnrollmentId(null);
        cred.setEnrollmentExpiresAt(null);
        cred.setVerifiedAt(LocalDateTime.now(clock));
        cred.setFailedAttempts(0);
        cred.setLockedUntil(null);
        cred.setLastAcceptedTimeStep(matchedTimeStep);
        repository.save(cred);
        return stepUpAuthenticationService.grantOwnerSecureSession(accountId);
    }

    @Transactional(noRollbackFor = TotpException.class)
    public void confirmEnrollmentForLogin(Long accountId, UUID enrollmentId, String code) {
        AccountTotpCredential cred = repository.findByAccountIdWithLock(accountId)
                .orElseThrow(() -> new TotpException("TOTP_ENROLLMENT_NOT_FOUND"));

        if (cred.isEnabled()) {
            throw new TotpException("TOTP_ALREADY_ENROLLED");
        }

        if (cred.getEnrollmentId() == null || !cred.getEnrollmentId().equals(enrollmentId)) {
            throw new TotpException("TOTP_ENROLLMENT_NOT_FOUND");
        }

        if (cred.getEnrollmentExpiresAt() != null && cred.getEnrollmentExpiresAt().isBefore(LocalDateTime.now(clock))) {
            throw new TotpException("TOTP_ENROLLMENT_EXPIRED");
        }

        if (cred.getLockedUntil() != null && cred.getLockedUntil().isAfter(LocalDateTime.now(clock))) {
            throw new TotpException("TOTP_ACCOUNT_LOCKED");
        }

        String secret = encryptionService.decrypt(
                cred.getEncryptedSecret(),
                cred.getEncryptionIv(),
                cred.getEncryptionKeyVersion(),
                accountId
        );

        long matchedTimeStep;
        try {
            matchedTimeStep = verificationService.verifyAndGetTimeStep(secret, code);
        } catch (TotpException e) {
            int fails = cred.getFailedAttempts() + 1;
            cred.setFailedAttempts(fails);
            if (fails >= totpProperties.getMaxFailedAttempts()) {
                cred.setLockedUntil(LocalDateTime.now(clock).plusMinutes(totpProperties.getLockDurationMinutes()));
            }
            repository.save(cred);
            throw e;
        }

        // Success - enable credential, clear enrollment state, update accepted time step
        cred.setEnabled(true);
        cred.setEnrollmentId(null);
        cred.setEnrollmentExpiresAt(null);
        cred.setVerifiedAt(LocalDateTime.now(clock));
        cred.setFailedAttempts(0);
        cred.setLockedUntil(null);
        cred.setLastAcceptedTimeStep(matchedTimeStep);
        repository.save(cred);
    }

    private static final int DISABLE_MFA_MAX_FAILED_ATTEMPTS = 5;
    private static final int DISABLE_MFA_COOLDOWN_MINUTES = 5;

    @Transactional(noRollbackFor = BusinessValidationException.class)
    public void disableTotp(Long accountId, String code) {
        // 1. Verify that the account exists and currently has 2FA enabled
        AccountTotpCredential cred = repository.findByAccountIdWithLock(accountId)
                .orElseThrow(() -> new BusinessValidationException("Two-factor authentication is not enabled."));

        if (!cred.isEnabled()) {
            throw new BusinessValidationException("Two-factor authentication is not enabled.");
        }

        LocalDateTime now = LocalDateTime.now(clock);

        // 2. Check for recent locked challenge within the 5-minute cooldown
        Optional<OtpChallenge> latestChallengeOpt = challengeRepository
                .findTopByAccountIdAndPurposeOrderByCreatedAtDesc(accountId, StepUpPurpose.DISABLE_2FA);

        if (latestChallengeOpt.isPresent()) {
            OtpChallenge latest = latestChallengeOpt.get();
            if (latest.getInvalidatedAt() != null && "LOCKED".equalsIgnoreCase(latest.getInvalidationReason())) {
                LocalDateTime lockTime = latest.getInvalidatedAt();
                LocalDateTime cooldownUntil = lockTime.plusMinutes(DISABLE_MFA_COOLDOWN_MINUTES);
                if (now.isBefore(cooldownUntil)) {
                    throw new BusinessValidationException("Too many failed attempts. Please try again later.");
                }
            }
        }

        // 3. Find or create an active (unlocked, unconsumed, unexpired) DISABLE_2FA challenge
        OtpChallenge challenge = challengeRepository
                .findTopByAccountIdAndPurposeAndUsedAtIsNullAndInvalidatedAtIsNullOrderByCreatedAtDesc(accountId, StepUpPurpose.DISABLE_2FA)
                .orElse(null);

        if (challenge != null && challenge.getExpiresAt().isBefore(now)) {
            challenge.setInvalidatedAt(now);
            challenge.setInvalidationReason("EXPIRED");
            challengeRepository.save(challenge);
            challenge = null;
        }

        if (challenge == null) {
            challenge = OtpChallenge.builder()
                    .accountId(accountId)
                    .purpose(StepUpPurpose.DISABLE_2FA)
                    .otpHash("TOTP")
                    .expiresAt(now.plusMinutes(DISABLE_MFA_COOLDOWN_MINUTES))
                    .attemptCount(0)
                    .maxAttempts(DISABLE_MFA_MAX_FAILED_ATTEMPTS)
                    .build();
            challenge = challengeRepository.save(challenge);
        }

        // 4. Validate OTP code format
        if (code == null || code.trim().length() != 6) {
            recordFailedDisableAttempt(challenge, now);
        }

        // 5. Decrypt secret and evaluate TOTP code
        String secret = encryptionService.decrypt(
                cred.getEncryptedSecret(),
                cred.getEncryptionIv(),
                cred.getEncryptionKeyVersion(),
                accountId
        );

        long matchedTimeStep;
        try {
            matchedTimeStep = verificationService.verifyAndGetTimeStep(secret, code.trim());
        } catch (Exception e) {
            recordFailedDisableAttempt(challenge, now);
            return;
        }

        // Replay check against lastAcceptedTimeStep
        if (cred.getLastAcceptedTimeStep() != null && matchedTimeStep <= cred.getLastAcceptedTimeStep()) {
            recordFailedDisableAttempt(challenge, now);
            return;
        }

        // 6. SUCCESS: Valid OTP before limit!
        // A) Consume the challenge immediately (single-use)
        challenge.setUsedAt(now);
        challenge.setInvalidatedAt(now);
        challenge.setInvalidationReason("CONSUMED");
        challengeRepository.save(challenge);

        // B) Disable 2FA on the credential and clear session flags
        cred.setEnabled(false);
        cred.setEnrollmentId(null);
        cred.setEnrollmentExpiresAt(null);
        cred.setVerifiedAt(null);
        cred.setFailedAttempts(0);
        cred.setLockedUntil(null);
        cred.setLastAcceptedTimeStep(matchedTimeStep);
        cred.setOwnerSecureSessionIssuedAt(null);
        cred.setOwnerSecureSessionExpiresAt(null);
        repository.save(cred);
    }

    private void recordFailedDisableAttempt(OtpChallenge challenge, LocalDateTime now) {
        int attempts = challenge.getAttemptCount() + 1;
        challenge.setAttemptCount(attempts);
        if (attempts >= DISABLE_MFA_MAX_FAILED_ATTEMPTS) {
            challenge.setInvalidatedAt(now);
            challenge.setInvalidationReason("LOCKED");
            challengeRepository.save(challenge);
            throw new BusinessValidationException("Too many failed attempts. Please try again later.");
        } else {
            challengeRepository.save(challenge);
            throw new BusinessValidationException("Invalid verification code. Please try again.");
        }
    }
}

