package com.apms.domain.admin.service;

import com.apms.common.enums.SystemRole;
import com.apms.domain.user.repository.sql.AccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminUserServiceCountByRoleM18Test {

    @Mock private AccountRepository accountRepository;
    @Mock private com.apms.domain.user.repository.sql.UserProfileRepository userProfileRepository;
    @Mock private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    @Mock private com.apms.domain.audit.repository.sql.AuditLogRepository auditLogRepository;

    @InjectMocks
    private AdminUserService adminUserService;

    @Test
    void countByRole_usesRepositoryMethod_notFindAll() {
        when(accountRepository.countByRolesContainingAndIsActiveTrue(any(SystemRole.class)))
                .thenReturn(0L);

        adminUserService.getRoles();

        verify(accountRepository, times(5)).countByRolesContainingAndIsActiveTrue(any(SystemRole.class));
        verify(accountRepository, never()).findAll();
    }
}
