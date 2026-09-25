package com.apms.domain.security.service;

import com.apms.domain.security.config.TotpProperties;
import com.apms.domain.security.entity.AccountTotpCredential;
import com.apms.domain.security.exception.TotpException;
import com.apms.domain.security.repository.AccountTotpCredentialRepository;
import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.HashingAlgorithm;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class TotpVerificationService {

    private final TotpProperties totpProperties;
    private final AccountTotpCredentialRepository repository;
    private final TotpSecretEncryptionService encryptionService;
    private final Clock clock;
    
    private final CodeGenerator codeGenerator = new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6);

    /**
     * Internal generic verification logic.
     * Returns the matched time step if valid, otherwise throws TotpException.
     */
    public long verifyAndGetTimeStep(String secret, String code) {
        long currentBucket = Math.floorDiv(clock.millis() / 1000L, totpProperties.getPeriodSeconds());
        int window = totpProperties.getAllowedWindow();

        for (int i = -window; i <= window; i++) {
            long timeStep = currentBucket + i;
            try {
                String expectedCode = codeGenerator.generate(secret, timeStep);
                // Constant time comparison (simple approach for strings is fine if padded, 
                // but MessageDigest.isEqual is better to prevent timing attacks).
                if (java.security.MessageDigest.isEqual(expectedCode.getBytes(), code.getBytes())) {
                    return timeStep;
                }
            } catch (dev.samstevens.totp.exceptions.CodeGenerationException e) {
                throw new TotpException("Error computing TOTP", e);
            }
        }
        throw new TotpException("TOTP_CODE_INVALID");
    }

    @Transactional(noRollbackFor = TotpException.class)
    public void verifyStepUpCode(Long accountId, String code) {
        verifyTotp(accountId, code);
    }

    @Transactional(noRollbackFor = TotpException.class)
    public void verifyTotp(Long accountId, String code) {
        AccountTotpCredential credential = repository.findByAccountIdWithLock(accountId)
                .orElseThrow(() -> new TotpException("TOTP_NOT_ENROLLED"));

        if (!credential.isEnabled()) {
            throw new TotpException("TOTP_NOT_ENROLLED");
        }

        if (credential.getLockedUntil() != null && credential.getLockedUntil().isAfter(LocalDateTime.now(clock))) {
            throw new TotpException("TOTP_ACCOUNT_LOCKED");
        }

        String secret = encryptionService.decrypt(
                credential.getEncryptedSecret(),
                credential.getEncryptionIv(),
                credential.getEncryptionKeyVersion(),
                accountId
        );

        long matchedTimeStep;
        try {
            matchedTimeStep = verifyAndGetTimeStep(secret, code);
        } catch (TotpException e) {
            handleFailedAttempt(credential);
            throw e;
        }

        if (credential.getLastAcceptedTimeStep() != null && matchedTimeStep <= credential.getLastAcceptedTimeStep()) {
            throw new TotpException("TOTP_CODE_REPLAYED");
        }

        // Success
        credential.setLastAcceptedTimeStep(matchedTimeStep);
        credential.setFailedAttempts(0);
        credential.setLockedUntil(null);
        repository.save(credential);
    }

    private void handleFailedAttempt(AccountTotpCredential credential) {
        int failures = credential.getFailedAttempts() + 1;
        credential.setFailedAttempts(failures);
        if (failures >= totpProperties.getMaxFailedAttempts()) {
            credential.setLockedUntil(LocalDateTime.now(clock).plusMinutes(totpProperties.getLockDurationMinutes()));
        }
        repository.save(credential);
    }
}
