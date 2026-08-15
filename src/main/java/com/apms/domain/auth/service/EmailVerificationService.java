package com.apms.domain.auth.service;

import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.security.entity.OtpChallenge;
import com.apms.domain.security.enums.StepUpPurpose;
import com.apms.domain.security.repository.OtpChallengeRepository;
import com.apms.domain.user.Account;
import com.apms.domain.user.UserProfile;
import com.apms.domain.user.repository.sql.AccountRepository;
import com.apms.domain.user.repository.sql.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;

@Service
@RequiredArgsConstructor
public class EmailVerificationService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final OtpChallengeRepository challengeRepository;
    private final AccountRepository accountRepository;
    private final UserProfileRepository profileRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailOtpMailService mailService;

    @Value("${apms.stepup.otp.expiration-seconds:300}")
    private long otpLifetimeSeconds;
    @Value("${apms.stepup.otp.max-attempts:5}")
    private int maxAttempts;
    @Value("${apms.stepup.otp.resend-cooldown-seconds:60}")
    private long resendCooldownSeconds;

    @Transactional
    public EmailIssueResult issue(Long accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessValidationException("Account not found"));
        challengeRepository.findByAccountIdAndPurposeAndUsedAtIsNullAndInvalidatedAtIsNull(accountId, StepUpPurpose.EMAIL_VERIFICATION)
                .forEach(existing -> { existing.setInvalidatedAt(LocalDateTime.now()); existing.setInvalidationReason("REPLACED"); challengeRepository.save(existing); });
        String otp = String.format("%06d", RANDOM.nextInt(1_000_000));
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(32));
        LocalDateTime now = LocalDateTime.now();
        OtpChallenge challenge = OtpChallenge.builder()
                .accountId(accountId).purpose(StepUpPurpose.EMAIL_VERIFICATION)
                .otpHash(passwordEncoder.encode(otp)).verificationTicketHash(passwordEncoder.encode(ticket))
                .expiresAt(now.plusSeconds(otpLifetimeSeconds)).ticketExpiresAt(now.plusSeconds(otpLifetimeSeconds))
                .maxAttempts(maxAttempts).build();
        challengeRepository.save(challenge);
        String name = profileRepository.findByAccountId(accountId).map(this::fullName).orElse("");
        EmailDeliveryResult delivery = mailService.sendVerificationCode(account.getEmail(), name, otp);
        return new EmailIssueResult(ticket, delivery);
    }

    @Transactional
    public void verify(String ticket, String otp) {
        OtpChallenge challenge = findTicket(ticket);
        if (challenge.getUsedAt() != null || challenge.getInvalidatedAt() != null) throw new BusinessValidationException("Verification code is no longer valid");
        if (challenge.getExpiresAt().isBefore(LocalDateTime.now())) throw new BusinessValidationException("Verification code has expired");
        if (challenge.getAttemptCount() >= challenge.getMaxAttempts()) throw new BusinessValidationException("Too many verification attempts");
        if (!passwordEncoder.matches(otp, challenge.getOtpHash())) {
            challenge.setAttemptCount(challenge.getAttemptCount() + 1); challengeRepository.save(challenge);
            throw new BusinessValidationException("Incorrect verification code");
        }
        Account account = accountRepository.findById(challenge.getAccountId()).orElseThrow(() -> new BusinessValidationException("Account not found"));
        account.setEmailVerified(true); accountRepository.save(account);
        challenge.setUsedAt(LocalDateTime.now()); challenge.setInvalidatedAt(LocalDateTime.now()); challenge.setInvalidationReason("VERIFIED"); challengeRepository.save(challenge);
    }

    @Transactional
    public EmailIssueResult resend(String ticket) {
        OtpChallenge challenge = findTicket(ticket);
        if (challenge.getTicketExpiresAt() != null && challenge.getTicketExpiresAt().isBefore(LocalDateTime.now())) throw new BusinessValidationException("Verification ticket has expired");
        if (challenge.getCreatedAt() != null && challenge.getCreatedAt().plusSeconds(resendCooldownSeconds).isAfter(LocalDateTime.now())) throw new BusinessValidationException("Please wait before requesting another code");
        return issue(challenge.getAccountId());
    }

    private OtpChallenge findTicket(String ticket) {
        if (ticket == null || ticket.isBlank()) throw new BusinessValidationException("Invalid verification ticket");
        return challengeRepository.findByPurposeAndUsedAtIsNullAndInvalidatedAtIsNull(StepUpPurpose.EMAIL_VERIFICATION).stream()
                .filter(c -> c.getTicketExpiresAt() != null && c.getTicketExpiresAt().isAfter(LocalDateTime.now()) && passwordEncoder.matches(ticket, c.getVerificationTicketHash()))
                .findFirst().orElseThrow(() -> new BusinessValidationException("Invalid verification ticket"));
    }

    private byte[] randomBytes(int length) { byte[] bytes = new byte[length]; RANDOM.nextBytes(bytes); return bytes; }
    private String fullName(UserProfile p) { return ((p.getFirstName() == null ? "" : p.getFirstName()) + " " + (p.getLastName() == null ? "" : p.getLastName())).trim(); }
}
