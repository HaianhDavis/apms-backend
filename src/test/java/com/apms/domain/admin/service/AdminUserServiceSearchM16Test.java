package com.apms.domain.admin.service;

import com.apms.common.enums.SystemRole;
import com.apms.domain.admin.dto.AccountAdminResponse;
import com.apms.domain.user.Account;
import com.apms.domain.user.repository.sql.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminUserServiceSearchM16Test {

    @Mock private AccountRepository accountRepository;
    @Mock private com.apms.domain.user.repository.sql.UserProfileRepository userProfileRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private com.apms.domain.audit.repository.sql.AuditLogRepository auditLogRepository;

    @InjectMocks
    private AdminUserService adminUserService;

    private Account makeAccount(long id, String email) {
        Account a = new Account();
        a.setId(id);
        a.setEmail(email);
        a.setUsername(email.split("@")[0]);
        a.setRoles(java.util.Set.of(SystemRole.BUSINESS_DEVELOPMENT_STAFF));
        a.setIsActive(true);
        return a;
    }

    @Test
    void searchAccountsByEmail_withQuery_filtersByEmail() {
        when(accountRepository.findByEmailContainingIgnoreCase("alice"))
                .thenReturn(List.of(makeAccount(1L, "alice@example.com")));

        List<AccountAdminResponse> results = adminUserService.searchAccountsByEmail("alice");

        assertEquals(1, results.size());
        assertEquals("alice@example.com", results.get(0).getEmail());
    }

    @Test
    void searchAccountsByEmail_emptyQuery_returnsAll() {
        when(accountRepository.findAll())
                .thenReturn(List.of(
                        makeAccount(1L, "a@x.com"),
                        makeAccount(2L, "b@x.com")
                ));

        List<AccountAdminResponse> results = adminUserService.searchAccountsByEmail("");

        assertEquals(2, results.size());
    }
}
