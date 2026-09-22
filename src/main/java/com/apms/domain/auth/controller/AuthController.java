package com.apms.domain.auth.controller;

import com.apms.common.response.ApiResponse;
import com.apms.domain.auth.RefreshToken;
import com.apms.domain.auth.dto.JwtResponse;
import com.apms.domain.auth.dto.LoginRequest;
import com.apms.domain.auth.dto.TokenRefreshRequest;
import com.apms.domain.auth.dto.EmailOtpRequest;
import com.apms.domain.auth.dto.ResendEmailOtpRequest;
import com.apms.domain.auth.dto.EmailVerificationLoginResponse;
import com.apms.domain.auth.dto.LoginMfaChallengeResponse;
import com.apms.domain.auth.dto.MfaCancelRequest;
import com.apms.domain.auth.dto.MfaVerifyRequest;
import com.apms.domain.auth.service.EmailIssueResult;
import com.apms.domain.auth.service.EmailVerificationService;
import com.apms.domain.auth.service.LoginMfaService;
import com.apms.domain.auth.service.RefreshTokenService;
import com.apms.domain.security.service.StepUpAuthenticationService;
import com.apms.domain.user.Account;
import com.apms.security.JwtUtils;
import com.apms.security.UserDetailsImpl;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtUtils jwtUtils;
    private final RefreshTokenService refreshTokenService;
    private final StepUpAuthenticationService stepUpAuthenticationService;
    private final EmailVerificationService emailVerificationService;
    private final LoginMfaService loginMfaService;

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<?>> authenticateUser(@Valid @RequestBody LoginRequest loginRequest) {

        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(loginRequest.getEmail(), loginRequest.getPassword()));
        } catch (DisabledException ex) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(
                    "Tài khoản đã bị vô hiệu hóa. Vui lòng liên hệ quản trị viên."));
        } catch (org.springframework.security.authentication.BadCredentialsException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error(
                    "Invalid email or password."));
        }

        SecurityContextHolder.getContext().setAuthentication(authentication);

        UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();

        if (!userDetails.isEmailVerified()) {
            EmailIssueResult issueResult = emailVerificationService.issue(userDetails.getId());
            return ResponseEntity.status(403).body(ApiResponse.<EmailVerificationLoginResponse>builder()
                    .success(false).message("Email verification required")
                    .data(EmailVerificationLoginResponse.builder().requiresEmailVerification(true)
                            .verificationTicket(issueResult.ticket()).email(maskEmail(userDetails.getEmail()))
                            .emailDelivered(issueResult.delivery().delivered())
                            .emailDeliveryMessage(issueResult.delivery().reason()).build()).build());
        }

        // Mandatory MFA for all standard APMS accounts:
        // Do NOT issue access token or refresh token here.
        // Return limited challenge response for TOTP verification or enrollment.
        try {
            LoginMfaChallengeResponse challengeResponse = loginMfaService.createLoginChallenge(
                    userDetails.getId(), userDetails.getEmail());
            return ResponseEntity.ok(ApiResponse.success(challengeResponse));
        } catch (Exception ex) {
            log.error("Unexpected error creating login MFA challenge for user {}: {}", userDetails.getEmail(), ex.getMessage(), ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Unable to complete sign in. Please try again."));
        }
    }

    @PostMapping("/mfa/verify")
    public ResponseEntity<ApiResponse<JwtResponse>> verifyLoginMfa(@Valid @RequestBody MfaVerifyRequest request) {
        try {
            Account account = loginMfaService.verifyLoginMfa(request.getChallengeId(), request.getTotpCode());
            JwtResponse jwtResponse = finalizeSuccessfulLogin(account);
            return ResponseEntity.ok(ApiResponse.success(jwtResponse));
        } catch (com.apms.common.exception.BusinessValidationException ex) {
            // Keep normal safe validation messages (e.g. invalid code, expired challenge)
            throw ex;
        } catch (Exception ex) {
            log.error("Unexpected error verifying login MFA challenge {}: {}", request.getChallengeId(), ex.getMessage(), ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Unable to complete sign in. Please try again."));
        }
    }

    @PostMapping("/mfa/cancel")
    public ResponseEntity<ApiResponse<Void>> cancelLoginMfa(@Valid @RequestBody MfaCancelRequest request) {
        loginMfaService.cancelLoginMfa(request.getChallengeId());
        return ResponseEntity.ok(ApiResponse.success(null, "MFA challenge cancelled"));
    }

    private JwtResponse finalizeSuccessfulLogin(Account account) {
        String jwt = jwtUtils.generateJwtTokenFromUsername(account.getEmail());
        String refreshToken = refreshTokenService.createOrUpdateRefreshToken(account.getId());
        List<String> roles = account.getRoles().stream()
                .map(r -> "ROLE_" + r.name())
                .collect(Collectors.toList());

        return JwtResponse.builder()
                .accessToken(jwt)
                .refreshToken(refreshToken)
                .id(account.getId())
                .email(account.getEmail())
                .roles(roles)
                .build();
    }

    @PostMapping("/verify-email-otp")
    public ResponseEntity<ApiResponse<Void>> verifyEmailOtp(@Valid @RequestBody EmailOtpRequest request) {
        emailVerificationService.verify(request.getVerificationTicket(), request.getOtp());
        return ResponseEntity.ok(ApiResponse.success(null, "Email verified successfully"));
    }

    @PostMapping("/resend-email-otp")
    public ResponseEntity<ApiResponse<EmailVerificationLoginResponse>> resendEmailOtp(@Valid @RequestBody ResendEmailOtpRequest request) {
        EmailIssueResult issueResult = emailVerificationService.resend(request.getVerificationTicket());
        return ResponseEntity.ok(ApiResponse.success(EmailVerificationLoginResponse.builder()
                .requiresEmailVerification(true).verificationTicket(issueResult.ticket())
                .emailDelivered(issueResult.delivery().delivered())
                .emailDeliveryMessage(issueResult.delivery().reason()).build(), "Verification code sent"));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<JwtResponse>> refreshtoken(@Valid @RequestBody TokenRefreshRequest request) {
        String requestRefreshToken = request.getRefreshToken();

        RefreshToken refreshToken = refreshTokenService.findAndVerifyToken(requestRefreshToken);

        String newRefreshToken = refreshTokenService.createOrUpdateRefreshToken(refreshToken.getAccount().getId());
        String newAccessToken = jwtUtils.generateJwtTokenFromUsername(refreshToken.getAccount().getEmail());

        List<String> roles = refreshToken.getAccount().getRoles().stream()
                .map(r -> "ROLE_" + r.name())
                .collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.success(JwtResponse.builder()
                .accessToken(newAccessToken)
                .refreshToken(newRefreshToken)
                .id(refreshToken.getAccount().getId())
                .email(refreshToken.getAccount().getEmail())
                .roles(roles)
                .build()));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logoutUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof UserDetailsImpl userDetails) {
            refreshTokenService.revokeToken(userDetails.getId());
            stepUpAuthenticationService.invalidateOwnerSecureSession(userDetails.getId());
        }
        return ResponseEntity.ok(ApiResponse.success(null, "Log out successful!"));
    }

    private String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 1) return "***" + (at >= 0 ? email.substring(at) : "");
        return email.charAt(0) + "***" + email.substring(at);
    }
}
