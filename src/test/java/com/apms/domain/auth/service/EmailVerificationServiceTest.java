package com.apms.domain.auth.service;

import com.apms.domain.security.entity.OtpChallenge;
import com.apms.domain.security.enums.StepUpPurpose;
import com.apms.domain.security.repository.OtpChallengeRepository;
import com.apms.domain.user.Account;
import com.apms.domain.user.repository.sql.AccountRepository;
import com.apms.domain.user.repository.sql.UserProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmailVerificationServiceTest {
    @Mock OtpChallengeRepository challengeRepository;
    @Mock AccountRepository accountRepository;
    @Mock UserProfileRepository profileRepository;
    @Mock EmailOtpMailService mailService;

    private EmailVerificationService service;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @BeforeEach
    void setUp() {
        service = new EmailVerificationService(challengeRepository, accountRepository, profileRepository, encoder, mailService);
    }

    @Test
    void issueHashesOtpAndTicketAndSendsToAccountEmail() {
        Account account = Account.builder().id(7L).email("user@example.com").emailVerified(false).build();
        when(accountRepository.findById(7L)).thenReturn(Optional.of(account));
        when(challengeRepository.findByAccountIdAndPurposeAndUsedAtIsNullAndInvalidatedAtIsNull(7L, StepUpPurpose.EMAIL_VERIFICATION)).thenReturn(List.of());
        when(challengeRepository.save(any(OtpChallenge.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(mailService.sendVerificationCode(eq("user@example.com"), anyString(), anyString())).thenReturn(EmailDeliveryResult.smtpSuccess());

        EmailIssueResult result = service.issue(7L);

        ArgumentCaptor<OtpChallenge> captor = ArgumentCaptor.forClass(OtpChallenge.class);
        verify(challengeRepository).save(captor.capture());
        OtpChallenge challenge = captor.getValue();
        assertThat(result.ticket()).isNotBlank();
        assertThat(result.delivery().delivered()).isTrue();
        assertThat(challenge.getOtpHash()).isNotBlank().doesNotContain(result.ticket());
        assertThat(challenge.getVerificationTicketHash()).isNotBlank();
        assertThat(challenge.getPurpose()).isEqualTo(StepUpPurpose.EMAIL_VERIFICATION);
        verify(mailService).sendVerificationCode(eq("user@example.com"), anyString(), anyString());
    }

    @Test
    void issueStillReturnsTicketWhenEmailDeliveryFails() {
        Account account = Account.builder().id(7L).email("user@example.com").emailVerified(false).build();
        when(accountRepository.findById(7L)).thenReturn(Optional.of(account));
        when(challengeRepository.findByAccountIdAndPurposeAndUsedAtIsNullAndInvalidatedAtIsNull(7L, StepUpPurpose.EMAIL_VERIFICATION)).thenReturn(List.of());
        when(challengeRepository.save(any(OtpChallenge.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(mailService.sendVerificationCode(eq("user@example.com"), anyString(), anyString())).thenReturn(EmailDeliveryResult.failed("Email could not be sent"));

        EmailIssueResult result = service.issue(7L);

        assertThat(result.ticket()).isNotBlank();
        assertThat(result.delivery().delivered()).isFalse();
        assertThat(result.delivery().status()).isEqualTo("SMTP_FAILURE");
    }

    @Test
    void invalidTicketIsRejectedWithoutChangingAccount() {
        when(challengeRepository.findByPurposeAndUsedAtIsNullAndInvalidatedAtIsNull(StepUpPurpose.EMAIL_VERIFICATION)).thenReturn(List.of());

        assertThatThrownBy(() -> service.verify("invalid", "123456"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Invalid verification ticket");
        verifyNoInteractions(accountRepository);
    }
}
