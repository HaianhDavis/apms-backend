package com.apms.domain.auth.controller;

import com.apms.common.response.ApiResponse;
import com.apms.domain.auth.RefreshToken;
import com.apms.domain.auth.dto.JwtResponse;
import com.apms.domain.auth.dto.LoginRequest;
import com.apms.domain.auth.dto.TokenRefreshRequest;
import com.apms.domain.auth.dto.EmailOtpRequest;
import com.apms.domain.auth.dto.ResendEmailOtpRequest;
import com.apms.domain.auth.dto.EmailVerificationLoginResponse;
import com.apms.domain.auth.service.EmailIssueResult;
import com.apms.domain.auth.service.EmailVerificationService;
import com.apms.domain.auth.service.RefreshTokenService;
import com.apms.domain.security.service.StepUpAuthenticationService;
import com.apms.security.JwtUtils;
import com.apms.security.UserDetailsImpl;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtUtils jwtUtils;
    private final RefreshTokenService refreshTokenService;
    private final StepUpAuthenticationService stepUpAuthenticationService;
    private final EmailVerificationService emailVerificationService;

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<?>> authenticateUser(@Valid @RequestBody LoginRequest loginRequest) {

        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(loginRequest.getEmail(), loginRequest.getPassword()));
        } catch (DisabledException ex) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(
                    "Tài khoản đã bị vô hiệu hóa. Vui lòng liên hệ quản trị viên."));
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

        String jwt = jwtUtils.generateJwtToken(authentication);

        String refreshToken = refreshTokenService.createOrUpdateRefreshToken(userDetails.getId());

        List<String> roles = userDetails.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.success(JwtResponse.builder()
                .accessToken(jwt)
                .refreshToken(refreshToken)
                .id(userDetails.getId())
                .email(userDetails.getEmail())
                .roles(roles)
                .build()));
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
