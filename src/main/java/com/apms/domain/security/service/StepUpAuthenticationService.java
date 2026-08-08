package com.apms.domain.security.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.SystemRole;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.profile.service.CompanyProfileAccessService;
import com.apms.domain.security.dto.StepUpVerifyResponse;
import com.apms.domain.security.dto.TotpDto.StepUpStatusResponse;
import com.apms.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class StepUpAuthenticationService {

    private final StepUpTokenService tokenService;
    private final TotpVerificationService totpVerificationService;
    private final AuditLogService auditLogService;
    private final CompanyProfileAccessService companyProfileAccessService;

    @Value("${apms.stepup.token.expiration-seconds:600}")
    private long tokenExpirationSeconds;

    public StepUpStatusResponse getStepUpStatus(String scope, String resourceId) {
        UserDetailsImpl currentUser = getCurrentUser();

        if ("COMPANY_INTERNAL_NEWS".equals(scope)) {
            companyProfileAccessService.requireOwnerAccessibleOfficialCompanyProfile(resourceId, currentUser);
        }
        
        boolean required = true; // For now, assume step-up is always required for CONFIDENTIAL_COMPANY_NEWS
        boolean verified = false;
        LocalDateTime expiresAt = null;

        // In a real implementation, you would inspect the current valid token to see if it's verified.
        // Since we don't have the token in the GET request (it's in the client's state),
        // we rely on the client knowing if it has a token. But we provide this endpoint
        // for structural integrity.

        return StepUpStatusResponse.builder()
                .required(required)
                .verified(verified)
                .expiresAt(expiresAt)
                .scope(scope)
                .resourceId(resourceId)
                .build();
    }

    @Transactional
    public StepUpVerifyResponse verifyTotpStepUp(String code, String scope, String resourceId) {
        UserDetailsImpl currentUser = getCurrentUser();

        if (!hasRole(currentUser, SystemRole.BUSINESS_OWNER)) {
            auditLogService.log(currentUser.getId(), AuditAction.CONFIDENTIAL_NEWS_ACCESS_DENIED, "StepUpAuth", null, "User is not a BUSINESS_OWNER");
            throw new AccessDeniedException("User is not a BUSINESS_OWNER");
        }

        if ("COMPANY_INTERNAL_NEWS".equals(scope)) {
            companyProfileAccessService.requireOwnerAccessibleOfficialCompanyProfile(resourceId, currentUser);
        }

        try {
            totpVerificationService.verifyStepUpCode(currentUser.getId(), code);
        } catch (Exception e) {
            auditLogService.log(currentUser.getId(), AuditAction.TOTP_STEP_UP_FAILED, "StepUpAuth", null, "Failed TOTP step-up for scope: " + scope);
            throw e;
        }

        // Generate token bound to scope and resourceId
        String token = tokenService.generateTokenForScope(currentUser.getId(), scope, resourceId, tokenExpirationSeconds);
        
        auditLogService.log(currentUser.getId(), AuditAction.TOTP_STEP_UP_SUCCEEDED, "StepUpAuth", resourceId, "TOTP step-up succeeded for scope: " + scope);

        return StepUpVerifyResponse.builder()
                .stepUpToken(token)
                .expiresInSeconds(tokenExpirationSeconds)
                .build();
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
