package com.apms.domain.security.service;

import com.apms.domain.security.config.TotpProperties;
import com.apms.domain.security.dto.StepUpVerifyResponse;
import com.apms.domain.security.dto.TotpDto.TotpEnrollmentStartResponse;
import com.apms.domain.security.dto.TotpDto.TotpStatusResponse;
import com.apms.domain.security.entity.AccountTotpCredential;
import com.apms.domain.security.exception.TotpException;
import com.apms.domain.security.repository.AccountTotpCredentialRepository;
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
                .enrolled(true)
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

    @Transactional
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

    @Transactional
    public void resetEnrollment(Long accountId) {
        repository.findByAccountIdWithLock(accountId).ifPresent(cred -> {
            repository.delete(cred);
            repository.flush();
        });
    }
}
