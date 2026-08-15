package com.apms.domain.security.service;

import com.apms.common.enums.AuditAction;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.profile.CompanyProfile;
import com.apms.domain.profile.service.CompanyProfileAccessService;
import com.apms.domain.security.dto.StepUpVerifyResponse;
import com.apms.domain.security.dto.TotpDto.StepUpStatusResponse;
import com.apms.domain.security.entity.AccountTotpCredential;
import com.apms.domain.security.exception.TotpException;
import com.apms.domain.security.repository.AccountTotpCredentialRepository;
import com.apms.security.UserDetailsImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StepUpAuthenticationServiceTest {

    @Mock
    private StepUpTokenService tokenService;
    @Mock
    private TotpVerificationService totpVerificationService;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private CompanyProfileAccessService companyProfileAccessService;
    @Mock
    private AccountTotpCredentialRepository credentialRepository;

    @InjectMocks
    private StepUpAuthenticationService stepUpAuthenticationService;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String... roles) {
        List<SimpleGrantedAuthority> authorities = java.util.Arrays.stream(roles)
                .map(SimpleGrantedAuthority::new)
                .toList();
        UserDetailsImpl user = new UserDetailsImpl(1L, "owner@apms.vn", "x", authorities, true);
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(user, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private void authenticateOwner() {
        authenticateAs("ROLE_BUSINESS_OWNER");
    }

    private AccountTotpCredential enabledCredential() {
        return AccountTotpCredential.builder()
                .accountId(1L)
                .enabled(true)
                .ownerSecureSessionExpiresAt(LocalDateTime.now().plusSeconds(300))
                .build();
    }

    @Test
    void getStepUpStatus_unauthenticated_throwsAccessDenied() {
        assertThatThrownBy(() -> stepUpAuthenticationService.getStepUpStatus(null, null, null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void getStepUpStatus_nonOwner_throwsAccessDenied() {
        authenticateAs("ROLE_BUSINESS_DEVELOPMENT_STAFF");
        assertThatThrownBy(() -> stepUpAuthenticationService.getStepUpStatus(null, null, null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void getStepUpStatus_ownerWithoutCredential_reportsNotConfigured() {
        authenticateOwner();
        when(credentialRepository.findByAccountId(1L)).thenReturn(Optional.empty());

        StepUpStatusResponse status = stepUpAuthenticationService.getStepUpStatus(null, null, null);

        assertThat(status.isRequired()).isTrue();
        assertThat(status.isVerified()).isFalse();
        assertThat(status.isTotpConfigured()).isFalse();
        assertThat(status.isSecureAccessActive()).isFalse();
        assertThat(status.getExpiresAt()).isNull();
    }

    @Test
    void getStepUpStatus_configuredButNoActiveSession_reportsNotVerified() {
        authenticateOwner();
        when(credentialRepository.findByAccountId(1L))
                .thenReturn(Optional.of(enabledCredential()));

        StepUpStatusResponse status = stepUpAuthenticationService.getStepUpStatus(null, null, null);

        assertThat(status.isTotpConfigured()).isTrue();
        assertThat(status.isVerified()).isFalse();
        assertThat(status.isSecureAccessActive()).isFalse();
    }

    @Test
    void getStepUpStatus_configuredWithActiveSession_reportsVerified() {
        authenticateOwner();
        LocalDateTime tokenExpiry = LocalDateTime.now().plusSeconds(300);
        AccountTotpCredential credential = enabledCredential();
        credential.setOwnerSecureSessionExpiresAt(tokenExpiry.plusSeconds(300));
        when(credentialRepository.findByAccountId(1L))
                .thenReturn(Optional.of(credential));
        when(tokenService.validateOwnerSecureSessionToken("token", 1L)).thenReturn(true);
        when(tokenService.getOwnerSecureSessionExpiresAt("token", 1L))
                .thenReturn(tokenExpiry);

        StepUpStatusResponse status = stepUpAuthenticationService.getStepUpStatus(null, null, "token");

        assertThat(status.isVerified()).isTrue();
        assertThat(status.isSecureAccessActive()).isTrue();
        assertThat(status.getExpiresAt()).isNotNull();
    }

    @Test
    void getStepUpStatus_companyProfileDocumentsScope_requiresOwnerAccessibleProfile() {
        authenticateOwner();
        when(companyProfileAccessService.requireOwnerAccessibleOfficialCompanyProfile(eq("X"), any()))
                .thenReturn(CompanyProfile.builder().companyId("X").build());
        when(credentialRepository.findByAccountId(1L)).thenReturn(Optional.empty());

        StepUpStatusResponse status = stepUpAuthenticationService.getStepUpStatus(
                StepUpAuthenticationService.SCOPE_COMPANY_PROFILE_DOCUMENTS, "X", null);

        verify(companyProfileAccessService).requireOwnerAccessibleOfficialCompanyProfile(eq("X"), any());
        assertThat(status.isTotpConfigured()).isFalse();
    }

    @Test
    void verifyTotpStepUp_success_grantsSecureSessionAndAudits() {
        authenticateOwner();
        when(credentialRepository.findByAccountIdWithLock(1L))
                .thenReturn(Optional.of(enabledCredential()));
        when(tokenService.generateOwnerSecureSessionToken(eq(1L), any())).thenReturn("session-token");

        StepUpVerifyResponse response =
                stepUpAuthenticationService.verifyTotpStepUp("123456", null, null);

        assertThat(response.isSecureAccessGranted()).isTrue();
        assertThat(response.getStepUpToken()).isEqualTo("session-token");
        verify(auditLogService).log(eq(1L), eq(AuditAction.TOTP_STEP_UP_SUCCEEDED), any(), any(), any());
        verify(totpVerificationService).verifyStepUpCode(1L, "123456");
    }

    @Test
    void grantOwnerSecureSession_notEnrolled_throwsTotpException() {
        when(credentialRepository.findByAccountIdWithLock(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> stepUpAuthenticationService.grantOwnerSecureSession(1L))
                .isInstanceOf(TotpException.class)
                .hasMessage("TOTP_NOT_ENROLLED");
    }

    @Test
    void isOwnerSecureSessionActive_blankToken_returnsFalse() {
        assertThat(stepUpAuthenticationService.isOwnerSecureSessionActive(1L, "  ")).isFalse();
        assertThat(stepUpAuthenticationService.isOwnerSecureSessionActive(1L, null)).isFalse();
        verify(credentialRepository, never()).findByAccountId(any());
    }

    @Test
    void isOwnerSecureSessionActive_validTokenButExpiredCredential_returnsFalse() {
        AccountTotpCredential expired = AccountTotpCredential.builder()
                .accountId(1L)
                .enabled(true)
                .ownerSecureSessionExpiresAt(LocalDateTime.now().minusSeconds(10))
                .build();
        when(tokenService.validateOwnerSecureSessionToken("token", 1L)).thenReturn(true);
        when(credentialRepository.findByAccountId(1L)).thenReturn(Optional.of(expired));

        assertThat(stepUpAuthenticationService.isOwnerSecureSessionActive(1L, "token")).isFalse();
    }

    @Test
    void invalidateOwnerSecureSession_clearsSessionFields() {
        AccountTotpCredential credential = enabledCredential();
        when(credentialRepository.findByAccountIdWithLock(1L)).thenReturn(Optional.of(credential));
        when(credentialRepository.save(credential)).thenReturn(credential);

        stepUpAuthenticationService.invalidateOwnerSecureSession(1L);

        assertThat(credential.getOwnerSecureSessionIssuedAt()).isNull();
        assertThat(credential.getOwnerSecureSessionExpiresAt()).isNull();
        verify(credentialRepository).save(credential);
    }
}
