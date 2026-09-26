package com.apms.domain.security.service;

import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.security.config.TotpProperties;
import com.apms.domain.security.entity.AccountTotpCredential;
import com.apms.domain.security.entity.OtpChallenge;
import com.apms.domain.security.enums.StepUpPurpose;
import com.apms.domain.security.exception.TotpException;
import com.apms.domain.security.repository.AccountTotpCredentialRepository;
import com.apms.domain.security.repository.OtpChallengeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TotpDisableMfaTest {

    @Mock
    private AccountTotpCredentialRepository credentialRepository;
    @Mock
    private OtpChallengeRepository challengeRepository;
    @Mock
    private TotpSecretEncryptionService encryptionService;
    @Mock
    private TotpVerificationService verificationService;
    @Mock
    private StepUpAuthenticationService stepUpAuthenticationService;

    private Clock clock;
    private TotpProperties totpProperties;
    private TotpEnrollmentService enrollmentService;

    private final Long accountId = 100L;
    private AccountTotpCredential activeCredential;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-09-26T12:00:00Z"), ZoneId.of("UTC"));
        totpProperties = new TotpProperties();
        enrollmentService = new TotpEnrollmentService(
                credentialRepository,
                challengeRepository,
                encryptionService,
                verificationService,
                totpProperties,
                clock,
                stepUpAuthenticationService
        );

        activeCredential = AccountTotpCredential.builder()
                .id(1L)
                .accountId(accountId)
                .enabled(true)
                .encryptedSecret(new byte[]{1, 2, 3})
                .encryptionIv(new byte[16])
                .encryptionKeyVersion("v1")
                .build();
    }

    @Test
    @DisplayName("1. When 2FA is not enabled, disable request is rejected")
    void disableTotp_whenNotEnabled_throwsException() {
        AccountTotpCredential disabledCred = AccountTotpCredential.builder()
                .accountId(accountId)
                .enabled(false)
                .build();
        when(credentialRepository.findByAccountIdWithLock(accountId)).thenReturn(Optional.of(disabledCred));

        assertThatThrownBy(() -> enrollmentService.disableTotp(accountId, "123456"))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessage("Two-factor authentication is not enabled.");
    }

    @Test
    @DisplayName("2. Attempts 1-4 with invalid OTP increment attemptCount and reject with Invalid verification code")
    void disableTotp_attempts1to4_incrementsAttemptsAndRejects() {
        when(credentialRepository.findByAccountIdWithLock(accountId)).thenReturn(Optional.of(activeCredential));
        when(challengeRepository.findTopByAccountIdAndPurposeOrderByCreatedAtDesc(accountId, StepUpPurpose.DISABLE_2FA))
                .thenReturn(Optional.empty());

        OtpChallenge challenge = OtpChallenge.builder()
                .id(501L)
                .accountId(accountId)
                .purpose(StepUpPurpose.DISABLE_2FA)
                .otpHash("TOTP")
                .attemptCount(0)
                .maxAttempts(5)
                .expiresAt(LocalDateTime.now(clock).plusMinutes(5))
                .build();

        when(challengeRepository.findTopByAccountIdAndPurposeAndUsedAtIsNullAndInvalidatedAtIsNullOrderByCreatedAtDesc(accountId, StepUpPurpose.DISABLE_2FA))
                .thenReturn(Optional.of(challenge));
        when(encryptionService.decrypt(any(), any(), any(), eq(accountId))).thenReturn("plainSecret");
        when(verificationService.verifyAndGetTimeStep("plainSecret", "000000"))
                .thenThrow(new TotpException("TOTP_CODE_INVALID"));

        assertThatThrownBy(() -> enrollmentService.disableTotp(accountId, "000000"))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessage("Invalid verification code. Please try again.");

        assertThat(challenge.getAttemptCount()).isEqualTo(1);
        assertThat(challenge.getInvalidatedAt()).isNull();
        assertThat(activeCredential.isEnabled()).isTrue();
        verify(challengeRepository).save(challenge);
    }

    @Test
    @DisplayName("3. 5th failed attempt locks the disable challenge and rejects with Too many failed attempts")
    void disableTotp_attempt5_locksChallengeAndRejects() {
        when(credentialRepository.findByAccountIdWithLock(accountId)).thenReturn(Optional.of(activeCredential));
        when(challengeRepository.findTopByAccountIdAndPurposeOrderByCreatedAtDesc(accountId, StepUpPurpose.DISABLE_2FA))
                .thenReturn(Optional.empty());

        OtpChallenge challenge = OtpChallenge.builder()
                .id(502L)
                .accountId(accountId)
                .purpose(StepUpPurpose.DISABLE_2FA)
                .otpHash("TOTP")
                .attemptCount(4) // 4 prior failures
                .maxAttempts(5)
                .expiresAt(LocalDateTime.now(clock).plusMinutes(5))
                .build();

        when(challengeRepository.findTopByAccountIdAndPurposeAndUsedAtIsNullAndInvalidatedAtIsNullOrderByCreatedAtDesc(accountId, StepUpPurpose.DISABLE_2FA))
                .thenReturn(Optional.of(challenge));
        when(encryptionService.decrypt(any(), any(), any(), eq(accountId))).thenReturn("plainSecret");
        when(verificationService.verifyAndGetTimeStep("plainSecret", "000000"))
                .thenThrow(new TotpException("TOTP_CODE_INVALID"));

        assertThatThrownBy(() -> enrollmentService.disableTotp(accountId, "000000"))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessage("Too many failed attempts. Please try again later.");

        assertThat(challenge.getAttemptCount()).isEqualTo(5);
        assertThat(challenge.getInvalidatedAt()).isNotNull();
        assertThat(challenge.getInvalidationReason()).isEqualTo("LOCKED");
        assertThat(activeCredential.isEnabled()).isTrue(); // 2FA remains enabled
        verify(challengeRepository).save(challenge);
    }

    @Test
    @DisplayName("4. After 5th failed attempt within cooldown, even correct OTP is rejected and 2FA remains enabled")
    void disableTotp_whileLocked_rejectsEvenWithCorrectOtp() {
        when(credentialRepository.findByAccountIdWithLock(accountId)).thenReturn(Optional.of(activeCredential));

        LocalDateTime lockTime = LocalDateTime.now(clock).minusMinutes(2); // Locked 2 min ago (within 5-min cooldown)
        OtpChallenge lockedChallenge = OtpChallenge.builder()
                .id(503L)
                .accountId(accountId)
                .purpose(StepUpPurpose.DISABLE_2FA)
                .otpHash("TOTP")
                .attemptCount(5)
                .maxAttempts(5)
                .invalidatedAt(lockTime)
                .invalidationReason("LOCKED")
                .build();

        when(challengeRepository.findTopByAccountIdAndPurposeOrderByCreatedAtDesc(accountId, StepUpPurpose.DISABLE_2FA))
                .thenReturn(Optional.of(lockedChallenge));

        // Submit correct OTP code
        assertThatThrownBy(() -> enrollmentService.disableTotp(accountId, "123456"))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessage("Too many failed attempts. Please try again later.");

        // Verification service is NEVER called
        verify(verificationService, never()).verifyAndGetTimeStep(any(), any());
        // 2FA remains enabled
        assertThat(activeCredential.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("5. After 5-minute cooldown, user can start new disable request and succeed")
    void disableTotp_afterCooldown_allowsNewRequest() {
        when(credentialRepository.findByAccountIdWithLock(accountId)).thenReturn(Optional.of(activeCredential));

        LocalDateTime lockTime = LocalDateTime.now(clock).minusMinutes(6); // Locked 6 min ago (> 5-min cooldown)
        OtpChallenge oldLockedChallenge = OtpChallenge.builder()
                .id(504L)
                .accountId(accountId)
                .purpose(StepUpPurpose.DISABLE_2FA)
                .otpHash("TOTP")
                .attemptCount(5)
                .maxAttempts(5)
                .invalidatedAt(lockTime)
                .invalidationReason("LOCKED")
                .build();

        when(challengeRepository.findTopByAccountIdAndPurposeOrderByCreatedAtDesc(accountId, StepUpPurpose.DISABLE_2FA))
                .thenReturn(Optional.of(oldLockedChallenge));

        // No active unlocked challenge exists yet
        when(challengeRepository.findTopByAccountIdAndPurposeAndUsedAtIsNullAndInvalidatedAtIsNullOrderByCreatedAtDesc(accountId, StepUpPurpose.DISABLE_2FA))
                .thenReturn(Optional.empty());

        OtpChallenge newChallenge = OtpChallenge.builder()
                .id(505L)
                .accountId(accountId)
                .purpose(StepUpPurpose.DISABLE_2FA)
                .otpHash("TOTP")
                .attemptCount(0)
                .maxAttempts(5)
                .expiresAt(LocalDateTime.now(clock).plusMinutes(5))
                .build();
        when(challengeRepository.save(any(OtpChallenge.class))).thenReturn(newChallenge);

        when(encryptionService.decrypt(any(), any(), any(), eq(accountId))).thenReturn("plainSecret");
        when(verificationService.verifyAndGetTimeStep("plainSecret", "654321")).thenReturn(12345L);

        // User enters valid OTP
        enrollmentService.disableTotp(accountId, "654321");

        // Success: 2FA disabled, challenge consumed
        assertThat(activeCredential.isEnabled()).isFalse();
        verify(credentialRepository).save(activeCredential);
        verify(challengeRepository, atLeastOnce()).save(any(OtpChallenge.class));
    }

    @Test
    @DisplayName("6. Valid OTP before limit consumes challenge and disables 2FA")
    void disableTotp_validOtpBeforeLimit_consumesChallengeAndDisables2Fa() {
        when(credentialRepository.findByAccountIdWithLock(accountId)).thenReturn(Optional.of(activeCredential));
        when(challengeRepository.findTopByAccountIdAndPurposeOrderByCreatedAtDesc(accountId, StepUpPurpose.DISABLE_2FA))
                .thenReturn(Optional.empty());

        OtpChallenge challenge = OtpChallenge.builder()
                .id(506L)
                .accountId(accountId)
                .purpose(StepUpPurpose.DISABLE_2FA)
                .otpHash("TOTP")
                .attemptCount(1)
                .maxAttempts(5)
                .expiresAt(LocalDateTime.now(clock).plusMinutes(5))
                .build();

        when(challengeRepository.findTopByAccountIdAndPurposeAndUsedAtIsNullAndInvalidatedAtIsNullOrderByCreatedAtDesc(accountId, StepUpPurpose.DISABLE_2FA))
                .thenReturn(Optional.of(challenge));
        when(encryptionService.decrypt(any(), any(), any(), eq(accountId))).thenReturn("plainSecret");
        when(verificationService.verifyAndGetTimeStep("plainSecret", "123456")).thenReturn(99999L);

        enrollmentService.disableTotp(accountId, "123456");

        // Challenge consumed
        assertThat(challenge.getUsedAt()).isNotNull();
        assertThat(challenge.getInvalidatedAt()).isNotNull();
        assertThat(challenge.getInvalidationReason()).isEqualTo("CONSUMED");
        verify(challengeRepository).save(challenge);

        // 2FA disabled
        assertThat(activeCredential.isEnabled()).isFalse();
        assertThat(activeCredential.getLastAcceptedTimeStep()).isEqualTo(99999L);
        verify(credentialRepository).save(activeCredential);
    }
}
