package com.apms.domain.security.service;

import com.apms.common.enums.AuditAction;
import com.apms.common.enums.SystemRole;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.profile.service.CompanyProfileAccessService;
import com.apms.domain.security.dto.StepUpVerifyResponse;
import com.apms.domain.security.dto.TotpDto.StepUpStatusResponse;
import com.apms.domain.security.entity.AccountTotpCredential;
import com.apms.domain.security.exception.TotpException;
import com.apms.domain.security.repository.AccountTotpCredentialRepository;
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

    public static final String SCOPE_COMPANY_INTERNAL_NEWS = "COMPANY_INTERNAL_NEWS";

    private final StepUpTokenService tokenService;
    private final TotpVerificationService totpVerificationService;
    private final AuditLogService auditLogService;
    private final CompanyProfileAccessService companyProfileAccessService;
    private final AccountTotpCredentialRepository credentialRepository;


    @Value("${apms.stepup.token.expiration-seconds:600}")
    private long tokenExpirationSeconds;

    public StepUpStatusResponse getStepUpStatus(String scope, String resourceId, String ownerSecureToken) {
        UserDetailsImpl currentUser = getCurrentUser();

        boolean isOwner = hasRole(currentUser, SystemRole.BUSINESS_OWNER) || hasRole(currentUser, SystemRole.SYSTEM_ADMIN);
        boolean isManager = hasRole(currentUser, SystemRole.BUSINESS_DEVELOPMENT_MANAGER);

        if (!isOwner && !isManager) {
            throw new AccessDeniedException("User role is not authorized for secure access");
        }

        if (SCOPE_COMPANY_INTERNAL_NEWS.equals(scope)) {
            companyProfileAccessService.requireOwnerAccessibleOfficialCompanyProfile(resourceId, currentUser);
        } else if (scope == null && resourceId == null) {
            if (!isOwner) {
                throw new AccessDeniedException("Only BUSINESS_OWNER can access global secure scope");
            }
        } else {
            throw new AccessDeniedException("STEP_UP_SCOPE_FORBIDDEN");
        }

        AccountTotpCredential credential = credentialRepository.findByAccountId(currentUser.getId()).orElse(null);
        boolean configured = credential != null && credential.isEnabled();
        boolean verified = configured && isOwnerSecureSessionActive(currentUser.getId(), ownerSecureToken);
        LocalDateTime expiresAt = verified ? credential.getOwnerSecureSessionExpiresAt() : null;

        return StepUpStatusResponse.builder()
                .required(true)
                .verified(verified)
                .expiresAt(expiresAt)
                .secureAccessActive(verified)
                .totpConfigured(configured)
                .scope(scope)
                .resourceId(resourceId)
                .build();
    }

    @Transactional
    public StepUpVerifyResponse verifyTotpStepUp(String code, String scope, String resourceId) {
        UserDetailsImpl currentUser = getCurrentUser();

        boolean isOwner = hasRole(currentUser, SystemRole.BUSINESS_OWNER) || hasRole(currentUser, SystemRole.SYSTEM_ADMIN);
        boolean isManager = hasRole(currentUser, SystemRole.BUSINESS_DEVELOPMENT_MANAGER);

        if (!isOwner && !isManager) {
            auditLogService.log(currentUser.getId(), AuditAction.TOTP_STEP_UP_FAILED, "StepUpAuth", resourceId, "User is not authorized for scope: " + scope);
            throw new AccessDeniedException("User role is not authorized for secure access");
        }

        if (SCOPE_COMPANY_INTERNAL_NEWS.equals(scope)) {
            companyProfileAccessService.requireOwnerAccessibleOfficialCompanyProfile(resourceId, currentUser);
        } else if (scope == null && resourceId == null) {
            if (!isOwner) {
                auditLogService.log(currentUser.getId(), AuditAction.TOTP_STEP_UP_FAILED, "StepUpAuth", null, "Manager cannot verify global secure scope");
                throw new AccessDeniedException("Only BUSINESS_OWNER can access global secure scope");
            }
        } else {
            throw new AccessDeniedException("STEP_UP_SCOPE_FORBIDDEN");
        }

        try {
            totpVerificationService.verifyStepUpCode(currentUser.getId(), code);
        } catch (Exception e) {
            auditLogService.log(currentUser.getId(), AuditAction.TOTP_STEP_UP_FAILED, "StepUpAuth", null, "Failed TOTP step-up for scope: " + scope);
            throw e;
        }

        StepUpVerifyResponse response = grantOwnerSecureSession(currentUser.getId());

        auditLogService.log(currentUser.getId(), AuditAction.TOTP_STEP_UP_SUCCEEDED, "StepUpAuth", resourceId, "Secure session granted with TOTP");

        return response;
    }

    @Transactional
    public StepUpVerifyResponse grantOwnerSecureSession(Long accountId) {
        AccountTotpCredential credential = credentialRepository.findByAccountIdWithLock(accountId)
                .orElseThrow(() -> new TotpException("TOTP_NOT_ENROLLED"));
        if (!credential.isEnabled()) {
            throw new TotpException("TOTP_NOT_ENROLLED");
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusSeconds(tokenExpirationSeconds);
        credential.setOwnerSecureSessionIssuedAt(now);
        credential.setOwnerSecureSessionExpiresAt(expiresAt);
        credentialRepository.save(credential);

        String token = tokenService.generateOwnerSecureSessionToken(accountId, expiresAt);
        return StepUpVerifyResponse.builder()
                .stepUpToken(token)
                .expiresInSeconds(tokenExpirationSeconds)
                .expiresAt(expiresAt)
                .secureAccessGranted(true)
                .build();
    }

    @Transactional(readOnly = true)
    public boolean isOwnerSecureSessionActive(Long accountId, String ownerSecureToken) {
        if (ownerSecureToken == null || ownerSecureToken.isBlank()) {
            return false;
        }
        if (!tokenService.validateOwnerSecureSessionToken(ownerSecureToken, accountId)) {
            return false;
        }

        AccountTotpCredential credential = credentialRepository.findByAccountId(accountId).orElse(null);
        if (credential == null || !credential.isEnabled() || credential.getOwnerSecureSessionExpiresAt() == null) {
            return false;
        }
        if (!credential.getOwnerSecureSessionExpiresAt().isAfter(LocalDateTime.now())) {
            return false;
        }

        LocalDateTime tokenExpiresAt = tokenService.getOwnerSecureSessionExpiresAt(ownerSecureToken, accountId);
        return tokenExpiresAt != null && !tokenExpiresAt.isAfter(credential.getOwnerSecureSessionExpiresAt());
    }

    @Transactional
    public void invalidateOwnerSecureSession(Long accountId) {
        credentialRepository.findByAccountIdWithLock(accountId).ifPresent(credential -> {
            credential.setOwnerSecureSessionIssuedAt(null);
            credential.setOwnerSecureSessionExpiresAt(null);
            credentialRepository.save(credential);
        });
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
