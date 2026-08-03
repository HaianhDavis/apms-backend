package com.apms.domain.security.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.SystemRole;
import com.apms.common.exception.BusinessValidationException;
import com.apms.common.exception.ResourceNotFoundException;
import com.apms.common.exception.ServiceUnavailableException;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.security.dto.StepUpChallengeResponse;
import com.apms.domain.security.dto.StepUpVerifyResponse;
import com.apms.domain.security.entity.OtpChallenge;
import com.apms.domain.security.enums.StepUpPurpose;
import com.apms.domain.security.repository.OtpChallengeRepository;
import com.apms.domain.user.Account;
import com.apms.domain.user.repository.sql.AccountRepository;
import com.apms.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class StepUpAuthenticationService {

    private final AccountRepository accountRepository;
    private final OtpChallengeRepository challengeRepository;
    private final OtpDeliveryProvider deliveryProvider;
    private final StepUpTokenService tokenService;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;

    @Value("${apms.stepup.otp.pepper:}")
    private String otpPepper;

    @Value("${apms.stepup.otp.expiration-seconds:300}")
    private long otpExpirationSeconds;

    @Value("${apms.stepup.otp.max-attempts:5}")
    private int maxAttempts;

    @Value("${apms.stepup.otp.resend-cooldown-seconds:60}")
    private long resendCooldownSeconds;

    @Value("${apms.stepup.otp.max-challenges-per-15-minutes:5}")
    private int maxChallengesPer15Minutes;
    
    @Value("${apms.stepup.token.expiration-seconds:600}")
    private long tokenExpirationSeconds;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Transactional
    public StepUpChallengeResponse createChallenge(String purposeStr, String requestIp) {
        UserDetailsImpl currentUser = getCurrentUser();

        if (!hasRole(currentUser, SystemRole.BUSINESS_OWNER)) {
            auditLogService.log(currentUser.getId(), AuditAction.CONFIDENTIAL_NEWS_ACCESS_DENIED, "StepUpAuth", null, "User is not a BUSINESS_OWNER");
            throw new AccessDeniedException("User is not a BUSINESS_OWNER");
        }

        StepUpPurpose purpose;
        try {
            purpose = StepUpPurpose.valueOf(purposeStr);
        } catch (IllegalArgumentException e) {
            throw new BusinessValidationException("Invalid purpose: " + purposeStr);
        }

        Account account = accountRepository.findById(currentUser.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));

        if (account.getPhoneNumber() == null || account.getPhoneVerifiedAt() == null) {
            throw new BusinessValidationException("MFA_ENROLLMENT_REQUIRED");
        }

        if (!deliveryProvider.isAvailable()) {
            throw new ServiceUnavailableException("MFA_DELIVERY_UNAVAILABLE");
        }
        
        if (!tokenService.isConfigured()) {
            throw new ServiceUnavailableException("STEP_UP_NOT_CONFIGURED");
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime fifteenMinsAgo = now.minusMinutes(15);
        int challengesCreated = challengeRepository.countByAccountIdAndPurposeAndCreatedAtAfter(account.getId(), purpose, fifteenMinsAgo);
        
        if (challengesCreated >= maxChallengesPer15Minutes) {
            throw new BusinessValidationException("Rate limit exceeded for step-up challenges");
        }

        var activeChallenges = challengeRepository.findByAccountIdAndPurposeAndUsedAtIsNullAndInvalidatedAtIsNull(account.getId(), purpose);
        for (OtpChallenge challenge : activeChallenges) {
            if (challenge.getCreatedAt().plusSeconds(resendCooldownSeconds).isAfter(now)) {
                return StepUpChallengeResponse.builder()
                        .status("COOLDOWN_ACTIVE")
                        .build();
            }
            challenge.setInvalidatedAt(now);
            challenge.setInvalidationReason("RESEND_REQUESTED");
        }
        challengeRepository.saveAll(activeChallenges);

        String otp = String.format("%06d", SECURE_RANDOM.nextInt(1000000));
        String hashedOtp = passwordEncoder.encode(otp + otpPepper);

        OtpChallenge newChallenge = OtpChallenge.builder()
                .accountId(account.getId())
                .purpose(purpose)
                .otpHash(hashedOtp)
                .expiresAt(now.plusSeconds(otpExpirationSeconds))
                .maxAttempts(maxAttempts)
                .requestIp(requestIp)
                .createdAt(now)
                .build();
        newChallenge = challengeRepository.save(newChallenge);

        deliveryProvider.sendOtp(account.getPhoneNumber(), otp);

        auditLogService.log(currentUser.getId(), AuditAction.CONFIDENTIAL_NEWS_OTP_SENT, "OtpChallenge", String.valueOf(newChallenge.getId()), "OTP sent for " + purpose.name());

        return StepUpChallengeResponse.builder()
                .challengeId(newChallenge.getId())
                .maskedPhone(maskPhone(account.getPhoneNumber()))
                .expiresInSeconds(otpExpirationSeconds)
                .status("OTP_SENT")
                .build();
    }

    @Transactional
    public StepUpVerifyResponse verifyChallenge(Long challengeId, String otp) {
        UserDetailsImpl currentUser = getCurrentUser();

        OtpChallenge challenge = challengeRepository.findByIdAndAccountId(challengeId, currentUser.getId())
                .orElseThrow(() -> new BusinessValidationException("Challenge not found"));

        if (challenge.getInvalidatedAt() != null) {
             throw new BusinessValidationException("Challenge is invalidated: " + challenge.getInvalidationReason());
        }

        if (challenge.getUsedAt() != null) {
            throw new BusinessValidationException("Challenge already used");
        }

        if (challenge.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessValidationException("Challenge expired");
        }

        if (challenge.getAttemptCount() >= challenge.getMaxAttempts()) {
            throw new BusinessValidationException("Maximum attempts reached");
        }

        challenge.setAttemptCount(challenge.getAttemptCount() + 1);
        challengeRepository.save(challenge);

        if (!passwordEncoder.matches(otp + otpPepper, challenge.getOtpHash())) {
            throw new BusinessValidationException("Invalid OTP");
        }

        challenge.setUsedAt(LocalDateTime.now());
        challengeRepository.save(challenge);

        String token = tokenService.generateToken(currentUser.getId(), challenge.getPurpose());
        
        auditLogService.log(currentUser.getId(), AuditAction.CONFIDENTIAL_NEWS_OTP_VERIFIED, "OtpChallenge", String.valueOf(challenge.getId()), "OTP verified for " + challenge.getPurpose().name());

        return StepUpVerifyResponse.builder()
                .stepUpToken(token)
                .expiresInSeconds(tokenExpirationSeconds)
                .build();
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 4) {
            return "***";
        }
        return "***-***-" + phone.substring(phone.length() - 4);
    }

    private UserDetailsImpl getCurrentUser() {
        if (SecurityContextHolder.getContext().getAuthentication() == null ||
                !(SecurityContextHolder.getContext().getAuthentication().getPrincipal() instanceof UserDetailsImpl)) {
            throw new AccessDeniedException("Unauthorized");
        }
        return (UserDetailsImpl) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    private boolean hasRole(UserDetailsImpl user, SystemRole role) {
        return user.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_" + role.name()));
    }
}
