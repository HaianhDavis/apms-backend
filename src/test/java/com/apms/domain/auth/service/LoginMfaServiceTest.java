package com.apms.domain.auth.service;

import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.auth.dto.LoginMfaChallengeResponse;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LoginMfaServiceTest {

    @Mock
    private OtpChallengeRepository challengeRepository;
    @Mock
    private AccountRepository accountRepository;
    @Mock
    private AccountTotpCredentialRepository credentialRepository;
    @Mock
    private TotpEnrollmentService totpEnrollmentService;
    @Mock
    private TotpVerificationService totpVerificationService;

    private LoginMfaService service;

    @BeforeEach
    void setUp() {
        service = new LoginMfaService(
                challengeRepository,
                accountRepository,
                credentialRepository,
                totpEnrollmentService,
                totpVerificationService
        );
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("SCENARIO A — Wrong code below limit increments attemptCount and challenge remains ACTIVE")
    void wrongCodeBelowLimitIncrementsAttemptCount() {
        String secret = "validSecret1234567890123456789012";
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(5);

        OtpChallenge challenge = OtpChallenge.builder()
                .id(100L)
                .accountId(42L)
                .purpose(StepUpPurpose.LOGIN_MFA)
                .otpHash("TOTP")
                .verificationTicketHash(sha256(secret))
                .expiresAt(expiresAt)
                .ticketExpiresAt(expiresAt)
                .attemptCount(3)
                .maxAttempts(5)
                .build();

        String challengeId = "100_" + secret;

        when(challengeRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(challenge));
        doThrow(new TotpException("TOTP_CODE_INVALID")).when(totpVerificationService).verifyTotp(42L, "000000");

        assertThatThrownBy(() -> service.verifyLoginMfa(challengeId, "000000"))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessage("Incorrect verification code.");

        assertThat(challenge.getAttemptCount()).isEqualTo(4);
        assertThat(challenge.getInvalidatedAt()).isNull();
        assertThat(challenge.getInvalidationReason()).isNull();
        assertThat(challenge.getExpiresAt()).isEqualTo(expiresAt); // SCENARIO H: expiry not extended
        verify(challengeRepository).save(challenge);
    }

    @Test
    @DisplayName("SCENARIO B — Fifth wrong attempt locks the challenge immediately")
    void fifthWrongAttemptLocksChallengeImmediately() {
        String secret = "validSecret1234567890123456789012";
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(5);

        OtpChallenge challenge = OtpChallenge.builder()
                .id(101L)
                .accountId(42L)
                .purpose(StepUpPurpose.LOGIN_MFA)
                .otpHash("TOTP")
                .verificationTicketHash(sha256(secret))
                .expiresAt(expiresAt)
                .ticketExpiresAt(expiresAt)
                .attemptCount(4)
                .maxAttempts(5)
                .build();

        String challengeId = "101_" + secret;

        when(challengeRepository.findByIdForUpdate(101L)).thenReturn(Optional.of(challenge));
        doThrow(new TotpException("TOTP_CODE_INVALID")).when(totpVerificationService).verifyTotp(42L, "000000");

        assertThatThrownBy(() -> service.verifyLoginMfa(challengeId, "000000"))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessage("Too many incorrect verification attempts. Please sign in again.");

        assertThat(challenge.getAttemptCount()).isEqualTo(5);
        assertThat(challenge.getInvalidatedAt()).isNotNull();
        assertThat(challenge.getInvalidationReason()).isEqualTo("LOCKED");
        verify(challengeRepository).save(challenge);
    }

    @Test
    @DisplayName("SCENARIO C — Correct code after max attempts is rejected without evaluating TOTP")
    void correctCodeAfterMaxAttemptsIsRejectedWithoutEvaluatingTotp() {
        String secret = "validSecret1234567890123456789012";
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(5);

        OtpChallenge challenge = OtpChallenge.builder()
                .id(102L)
                .accountId(42L)
                .purpose(StepUpPurpose.LOGIN_MFA)
                .otpHash("TOTP")
                .verificationTicketHash(sha256(secret))
                .expiresAt(expiresAt)
                .ticketExpiresAt(expiresAt)
                .attemptCount(5)
                .maxAttempts(5)
                .invalidatedAt(LocalDateTime.now().minusMinutes(1))
                .invalidationReason("LOCKED")
                .build();

        String challengeId = "102_" + secret;

        when(challengeRepository.findByIdForUpdate(102L)).thenReturn(Optional.of(challenge));

        assertThatThrownBy(() -> service.verifyLoginMfa(challengeId, "123456"))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessage("Too many incorrect verification attempts. Please sign in again.");

        verify(totpVerificationService, never()).verifyTotp(any(), any());
    }

    @Test
    @DisplayName("SCENARIO D — Challenge expiry rejects request without evaluating TOTP")
    void expiredChallengeIsRejectedWithoutEvaluatingTotp() {
        String secret = "validSecret1234567890123456789012";
        LocalDateTime expiredTime = LocalDateTime.now().minusSeconds(10);

        OtpChallenge challenge = OtpChallenge.builder()
                .id(103L)
                .accountId(42L)
                .purpose(StepUpPurpose.LOGIN_MFA)
                .otpHash("TOTP")
                .verificationTicketHash(sha256(secret))
                .expiresAt(expiredTime)
                .ticketExpiresAt(expiredTime)
                .attemptCount(0)
                .maxAttempts(5)
                .build();

        String challengeId = "103_" + secret;

        when(challengeRepository.findByIdForUpdate(103L)).thenReturn(Optional.of(challenge));

        assertThatThrownBy(() -> service.verifyLoginMfa(challengeId, "123456"))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessage("Verification challenge has expired. Please sign in again.");

        assertThat(challenge.getInvalidatedAt()).isNotNull();
        assertThat(challenge.getInvalidationReason()).isEqualTo("EXPIRED");
        verify(totpVerificationService, never()).verifyTotp(any(), any());
    }

    @Test
    @DisplayName("SCENARIO E — Successful MFA consumes challenge and returns account")
    void successfulMfaConsumesChallengeAndReturnsAccount() {
        String secret = "validSecret1234567890123456789012";
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(5);

        OtpChallenge challenge = OtpChallenge.builder()
                .id(104L)
                .accountId(42L)
                .purpose(StepUpPurpose.LOGIN_MFA)
                .otpHash("TOTP")
                .verificationTicketHash(sha256(secret))
                .expiresAt(expiresAt)
                .ticketExpiresAt(expiresAt)
                .attemptCount(0)
                .maxAttempts(5)
                .build();

        String challengeId = "104_" + secret;

        Account account = Account.builder().id(42L).email("user@apms.com").isActive(true).build();

        when(challengeRepository.findByIdForUpdate(104L)).thenReturn(Optional.of(challenge));
        when(accountRepository.findById(42L)).thenReturn(Optional.of(account));
        doNothing().when(totpVerificationService).verifyTotp(42L, "123456");

        Account verified = service.verifyLoginMfa(challengeId, "123456");

        assertThat(verified).isEqualTo(account);
        assertThat(challenge.getUsedAt()).isNotNull();
        assertThat(challenge.getInvalidatedAt()).isNotNull();
        assertThat(challenge.getInvalidationReason()).isEqualTo("CONSUMED");
        verify(challengeRepository).save(challenge);
    }

    @Test
    @DisplayName("SCENARIO F — Replay of consumed challenge is rejected without evaluating TOTP")
    void replayOfConsumedChallengeIsRejected() {
        String secret = "validSecret1234567890123456789012";
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(5);

        OtpChallenge challenge = OtpChallenge.builder()
                .id(105L)
                .accountId(42L)
                .purpose(StepUpPurpose.LOGIN_MFA)
                .otpHash("TOTP")
                .verificationTicketHash(sha256(secret))
                .expiresAt(expiresAt)
                .ticketExpiresAt(expiresAt)
                .attemptCount(0)
                .maxAttempts(5)
                .usedAt(LocalDateTime.now().minusSeconds(30))
                .invalidatedAt(LocalDateTime.now().minusSeconds(30))
                .invalidationReason("CONSUMED")
                .build();

        String challengeId = "105_" + secret;

        when(challengeRepository.findByIdForUpdate(105L)).thenReturn(Optional.of(challenge));

        assertThatThrownBy(() -> service.verifyLoginMfa(challengeId, "123456"))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessage("Verification session is no longer valid. Please sign in again.");

        verify(totpVerificationService, never()).verifyTotp(any(), any());
    }

    @Test
    @DisplayName("SCENARIO H — Incorrect attempts do not refresh or extend expiresAt")
    void incorrectAttemptsDoNotExtendExpiresAt() {
        String secret = "validSecret1234567890123456789012";
        LocalDateTime fixedExpiresAt = LocalDateTime.now().plusMinutes(3);

        OtpChallenge challenge = OtpChallenge.builder()
                .id(106L)
                .accountId(42L)
                .purpose(StepUpPurpose.LOGIN_MFA)
                .otpHash("TOTP")
                .verificationTicketHash(sha256(secret))
                .expiresAt(fixedExpiresAt)
                .ticketExpiresAt(fixedExpiresAt)
                .attemptCount(1)
                .maxAttempts(5)
                .build();

        String challengeId = "106_" + secret;

        when(challengeRepository.findByIdForUpdate(106L)).thenReturn(Optional.of(challenge));
        doThrow(new TotpException("TOTP_CODE_INVALID")).when(totpVerificationService).verifyTotp(42L, "999999");

        assertThatThrownBy(() -> service.verifyLoginMfa(challengeId, "999999"))
                .isInstanceOf(BusinessValidationException.class);

        assertThat(challenge.getExpiresAt()).isEqualTo(fixedExpiresAt);
    }
}
