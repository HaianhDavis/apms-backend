package com.apms.domain.user.service;

import com.apms.common.enums.SystemRole;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.user.Account;
import com.apms.domain.user.UserProfile;
import com.apms.domain.user.dto.UpdateUserRequest;
import com.apms.domain.user.dto.UserProfileResponse;
import com.apms.domain.user.repository.sql.AccountRepository;
import com.apms.domain.user.repository.sql.UserProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceSelfUpdateM8Test {

    @Mock private AccountRepository accountRepository;
    @Mock private UserProfileRepository userProfileRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AuditLogService auditLogService;

    @InjectMocks
    private UserService userService;

    private Account account;
    private UserProfile profile;

    @BeforeEach
    void setUp() {
        account = Account.builder()
                .id(1L).email("user@test.com")
                .roles(java.util.Set.of(SystemRole.BUSINESS_DEVELOPMENT_STAFF))
                .isActive(true).build();
        profile = new UserProfile();
        profile.setAccount(account);
        profile.setFirstName("Old Name");
        profile.setLastName("");
    }

    @Test
    void updateSelfProfile_updatesNameAndEmail() {
        when(accountRepository.findById(1L)).thenReturn(Optional.of(account));
        when(accountRepository.existsByEmail("new@test.com")).thenReturn(false);
        when(userProfileRepository.findByAccountId(1L)).thenReturn(Optional.of(profile));
        when(accountRepository.save(any())).thenReturn(account);
        when(userProfileRepository.save(any())).thenReturn(profile);

        UpdateUserRequest req = new UpdateUserRequest();
        req.setFullName("New Name");
        req.setEmail("new@test.com");

        UserProfileResponse resp = userService.updateSelfProfile(1L, req);

        assertEquals("new@test.com", resp.getEmail());
        assertEquals("New Name", resp.getFullName());
        verify(accountRepository).save(account);
        verify(userProfileRepository).save(profile);
    }

    @Test
    void updateSelfProfile_duplicateEmail_throws() {
        when(accountRepository.findById(1L)).thenReturn(Optional.of(account));
        when(accountRepository.existsByEmail("taken@test.com")).thenReturn(true);

        UpdateUserRequest req = new UpdateUserRequest();
        req.setFullName("Name");
        req.setEmail("taken@test.com");

        assertThrows(IllegalArgumentException.class, () -> userService.updateSelfProfile(1L, req));
    }
}
