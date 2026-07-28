package com.apms.domain.auth.service;

import com.apms.common.exception.ResourceNotFoundException;
import com.apms.domain.auth.PasswordResetToken;
import com.apms.domain.auth.dto.ChangePasswordRequest;
import com.apms.domain.auth.dto.ForgotPasswordRequest;
import com.apms.domain.auth.dto.ResetPasswordRequest;
import com.apms.domain.auth.repository.sql.PasswordResetTokenRepository;
import com.apms.domain.user.Account;
import com.apms.domain.user.repository.sql.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final AccountRepository accountRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public void processForgotPassword(ForgotPasswordRequest request, String originUrl) {
        Optional<Account> accountOpt = accountRepository.findByEmail(request.getEmail());
        if (accountOpt.isEmpty()) {
            return; // Don't reveal if email exists or not
        }
        
        Account account = accountOpt.get();
        passwordResetTokenRepository.deleteByAccount(account);
        
        String token = UUID.randomUUID().toString();
        PasswordResetToken resetToken = PasswordResetToken.builder()
                .account(account)
                .token(token)
                .expiryDate(Instant.now().plus(15, ChronoUnit.MINUTES))
                .build();
                
        passwordResetTokenRepository.save(resetToken);
        
        String resetUrl = originUrl + "/reset-password?token=" + token;
        emailService.sendPasswordResetEmail(account.getEmail(), resetUrl);
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        PasswordResetToken resetToken = passwordResetTokenRepository.findByToken(request.getToken())
                .orElseThrow(() -> new IllegalArgumentException("Invalid token"));
                
        if (resetToken.isExpired()) {
            passwordResetTokenRepository.delete(resetToken);
            throw new IllegalArgumentException("Token has expired");
        }
        
        Account account = resetToken.getAccount();
        account.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        accountRepository.save(account);
        
        passwordResetTokenRepository.delete(resetToken);
    }

    @Transactional
    public void changePassword(Long accountId, ChangePasswordRequest request) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));
                
        if (!passwordEncoder.matches(request.getCurrentPassword(), account.getPasswordHash())) {
            throw new IllegalArgumentException("Incorrect current password");
        }
        
        account.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        accountRepository.save(account);
    }
}
