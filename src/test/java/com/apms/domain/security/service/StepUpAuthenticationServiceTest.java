package com.apms.domain.security.service;

import com.apms.common.enums.SystemRole;
import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.security.entity.OtpChallenge;
import com.apms.domain.security.enums.StepUpPurpose;
import com.apms.domain.security.repository.OtpChallengeRepository;
import com.apms.domain.user.Account;
import com.apms.domain.user.repository.sql.AccountRepository;
import com.apms.security.UserDetailsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StepUpAuthenticationServiceTest {

    @Mock
    private AccountRepository accountRepository;
    @Mock
    private OtpChallengeRepository challengeRepository;
    @Mock
    private OtpDeliveryProvider deliveryProvider;
    @Mock
    private StepUpTokenService tokenService;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private StepUpAuthenticationService authService;

    @Mock
    private SecurityContext securityContext;
    @Mock
    private Authentication authentication;

    private UserDetailsImpl ownerUser;
    private Account ownerAccount;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(authService, "otpPepper", "pepper123");
        ReflectionTestUtils.setField(authService, "otpExpirationSeconds", 300);
        ReflectionTestUtils.setField(authService, "maxAttempts", 5);
        ReflectionTestUtils.setField(authService, "resendCooldownSeconds", 60);
        ReflectionTestUtils.setField(authService, "maxChallengesPer15Minutes", 5);
        ReflectionTestUtils.setField(authService, "tokenExpirationSeconds", 600);

        ownerUser = new UserDetailsImpl(
                1L, "owner@apms.com", "pass",
                List.of(new SimpleGrantedAuthority("ROLE_BUSINESS_OWNER")),
                true
        );
        ownerAccount = Account.builder()
                .id(1L)
                .phoneNumber("1234567890")
                .phoneVerifiedAt(LocalDateTime.now())
                .build();
    }

    private void mockSecurityContext(UserDetailsImpl user) {
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.getPrincipal()).thenReturn(user);
        SecurityContextHolder.setContext(securityContext);
    }

    @Test
    void createChallenge_Success() {
        mockSecurityContext(ownerUser);
        when(accountRepository.findById(1L)).thenReturn(Optional.of(ownerAccount));
        when(deliveryProvider.isAvailable()).thenReturn(true);
        when(tokenService.isConfigured()).thenReturn(true);
        when(challengeRepository.countByAccountIdAndPurposeAndCreatedAtAfter(eq(1L), eq(StepUpPurpose.CONFIDENTIAL_COMPANY_NEWS), any())).thenReturn(0);
        
        OtpChallenge savedChallenge = OtpChallenge.builder().id(100L).build();
        when(challengeRepository.save(any(OtpChallenge.class))).thenReturn(savedChallenge);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");

        var response = authService.createChallenge("CONFIDENTIAL_COMPANY_NEWS", "127.0.0.1");

        assertNotNull(response);
        assertEquals(100L, response.getChallengeId());
        assertEquals("***-***-7890", response.getMaskedPhone());
        
        verify(deliveryProvider).sendOtp(eq("1234567890"), anyString());
    }

    @Test
    void createChallenge_FailsIfNotOwner() {
        UserDetailsImpl staff = new UserDetailsImpl(
                2L, "staff@apms.com", "pass",
                List.of(new SimpleGrantedAuthority("ROLE_BUSINESS_DEVELOPMENT_STAFF")),
                true
        );
        mockSecurityContext(staff);

        assertThrows(AccessDeniedException.class, () -> 
                authService.createChallenge("CONFIDENTIAL_COMPANY_NEWS", "127.0.0.1"));
    }

    @Test
    void verifyChallenge_Success() {
        mockSecurityContext(ownerUser);
        
        OtpChallenge challenge = OtpChallenge.builder()
                .id(100L)
                .accountId(1L)
                .purpose(StepUpPurpose.CONFIDENTIAL_COMPANY_NEWS)
                .otpHash("hashed")
                .expiresAt(LocalDateTime.now().plusMinutes(5))
                .maxAttempts(5)
                .attemptCount(0)
                .build();
                
        when(challengeRepository.findByIdAndAccountId(100L, 1L)).thenReturn(Optional.of(challenge));
        when(passwordEncoder.matches("123456pepper123", "hashed")).thenReturn(true);
        when(tokenService.generateToken(1L, StepUpPurpose.CONFIDENTIAL_COMPANY_NEWS)).thenReturn("token123");

        var response = authService.verifyChallenge(100L, "123456");

        assertNotNull(response);
        assertEquals("token123", response.getStepUpToken());
        assertNotNull(challenge.getUsedAt());
        verify(challengeRepository, times(2)).save(challenge);
    }

    @Test
    void createChallenge_FailsIfExpired() {
        mockSecurityContext(ownerUser);
        
        OtpChallenge challenge = OtpChallenge.builder()
                .id(100L)
                .accountId(1L)
                .expiresAt(LocalDateTime.now().minusMinutes(1))
                .build();
                
        when(challengeRepository.findByIdAndAccountId(100L, 1L)).thenReturn(Optional.of(challenge));

        assertThrows(BusinessValidationException.class, () -> 
                authService.verifyChallenge(100L, "123456"));
    }

    @Test
    void createChallenge_Returns503WhenSmsProviderMissing() {
        mockSecurityContext(ownerUser);
        when(accountRepository.findById(1L)).thenReturn(Optional.of(ownerAccount));
        when(deliveryProvider.isAvailable()).thenReturn(false);

        com.apms.common.exception.ServiceUnavailableException exception = assertThrows(com.apms.common.exception.ServiceUnavailableException.class, () ->
                authService.createChallenge("CONFIDENTIAL_COMPANY_NEWS", "127.0.0.1"));
        assertEquals("MFA_DELIVERY_UNAVAILABLE", exception.getMessage());
    }

    @Test
    void createChallenge_Returns503WhenStepUpNotConfigured() {
        mockSecurityContext(ownerUser);
        when(accountRepository.findById(1L)).thenReturn(Optional.of(ownerAccount));
        when(deliveryProvider.isAvailable()).thenReturn(true);
        when(tokenService.isConfigured()).thenReturn(false);

        com.apms.common.exception.ServiceUnavailableException exception = assertThrows(com.apms.common.exception.ServiceUnavailableException.class, () ->
                authService.createChallenge("CONFIDENTIAL_COMPANY_NEWS", "127.0.0.1"));
        assertEquals("STEP_UP_NOT_CONFIGURED", exception.getMessage());
    }
}
