package com.apms.domain.user.service;

import com.apms.common.enums.SystemRole;
import com.apms.common.exception.BusinessValidationException;
import com.apms.domain.audit.service.AuditLogService;
import com.apms.domain.user.Account;
import com.apms.domain.user.dto.AssignUserRolesRequest;
import com.apms.domain.user.dto.UpdateUserStatusRequest;
import com.apms.domain.user.repository.sql.AccountRepository;
import com.apms.domain.user.repository.sql.UserProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceSecurityRulesTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private UserProfileRepository userProfileRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private UserService userService;

    private Account adminAccount;
    private Account ownerAccount;
    private Account managerAccount;

    @BeforeEach
    void setUp() {
        adminAccount = Account.builder()
                .id(1L)
                .email("admin@apms.com")
                .isActive(true)
                .roles(Set.of(SystemRole.SYSTEM_ADMIN))
                .build();

        ownerAccount = Account.builder()
                .id(2L)
                .email("owner@apms.com")
                .isActive(true)
                .roles(Set.of(SystemRole.BUSINESS_OWNER))
                .build();

        managerAccount = Account.builder()
                .id(3L)
                .email("manager@apms.com")
                .isActive(true)
                .roles(Set.of(SystemRole.BUSINESS_DEVELOPMENT_MANAGER))
                .build();
    }

    // ─────────────────────────────────────────────
    // Case 1: Admin gán role Owner/Admin cho người khác → THÀNH CÔNG
    // ─────────────────────────────────────────────
    @Test
    @DisplayName("Case 1: Admin (rank 6) gán role Owner/Admin cho người khác → THÀNH CÔNG")
    void adminAssignsOwnerOrAdmin_shouldSucceed() {
        when(accountRepository.findById(3L)).thenReturn(Optional.of(managerAccount));
        when(accountRepository.findById(1L)).thenReturn(Optional.of(adminAccount));

        AssignUserRolesRequest reqOwner = new AssignUserRolesRequest();
        reqOwner.setRoles(Set.of(SystemRole.BUSINESS_OWNER));
        assertDoesNotThrow(() -> userService.assignUserRoles(3L, reqOwner, 1L));

        AssignUserRolesRequest reqAdmin = new AssignUserRolesRequest();
        reqAdmin.setRoles(Set.of(SystemRole.SYSTEM_ADMIN));
        assertDoesNotThrow(() -> userService.assignUserRoles(3L, reqAdmin, 1L));

        verify(accountRepository, times(2)).save(managerAccount);
    }

    // ─────────────────────────────────────────────
    // Case 2: Owner gán role Admin hoặc Owner cho người khác → LỖI 403
    // ─────────────────────────────────────────────
    @Test
    @DisplayName("Case 2: Owner (rank 5) gán role Admin (6) hoặc Owner (5) cho người khác → LỖI 403")
    void ownerAssignsAdminOrOwner_shouldFailWith403() {
        when(accountRepository.findById(3L)).thenReturn(Optional.of(managerAccount));
        when(accountRepository.findById(2L)).thenReturn(Optional.of(ownerAccount));

        AssignUserRolesRequest reqAdmin = new AssignUserRolesRequest();
        reqAdmin.setRoles(Set.of(SystemRole.SYSTEM_ADMIN));
        AccessDeniedException exAdmin = assertThrows(AccessDeniedException.class, () ->
                userService.assignUserRoles(3L, reqAdmin, 2L));
        assertTrue(exAdmin.getMessage().contains("Bạn không có quyền gán role cao hơn hoặc ngang cấp mình"));

        AssignUserRolesRequest reqOwner = new AssignUserRolesRequest();
        reqOwner.setRoles(Set.of(SystemRole.BUSINESS_OWNER));
        AccessDeniedException exOwner = assertThrows(AccessDeniedException.class, () ->
                userService.assignUserRoles(3L, reqOwner, 2L));
        assertTrue(exOwner.getMessage().contains("Bạn không có quyền gán role cao hơn hoặc ngang cấp mình"));

        verify(accountRepository, never()).save(any());
    }

    // ─────────────────────────────────────────────
    // Case 3: Owner gán role Director/Manager/KeyMember/Staff → THÀNH CÔNG
    // ─────────────────────────────────────────────
    @Test
    @DisplayName("Case 3: Owner (rank 5) gán role Director/Manager/KeyMember/Staff → THÀNH CÔNG")
    void ownerAssignsLowerRoles_shouldSucceed() {
        when(accountRepository.findById(3L)).thenReturn(Optional.of(managerAccount));
        when(accountRepository.findById(2L)).thenReturn(Optional.of(ownerAccount));

        SystemRole[] lowerRoles = {
                SystemRole.BUSINESS_DIRECTOR,
                SystemRole.BUSINESS_DEVELOPMENT_MANAGER,
                SystemRole.KEY_MEMBER,
                SystemRole.BUSINESS_DEVELOPMENT_STAFF
        };

        for (SystemRole role : lowerRoles) {
            AssignUserRolesRequest req = new AssignUserRolesRequest();
            req.setRoles(Set.of(role));
            assertDoesNotThrow(() -> userService.assignUserRoles(3L, req, 2L));
        }

        verify(accountRepository, times(4)).save(managerAccount);
    }

    // ─────────────────────────────────────────────
    // Case 4: User tự khóa chính mình → bị CHẶN
    // ─────────────────────────────────────────────
    @Test
    @DisplayName("Case 4: User tự khóa chính mình → bị CHẶN (BusinessValidationException)")
    void userSelfLock_shouldBeBlocked() {
        when(accountRepository.findById(1L)).thenReturn(Optional.of(adminAccount));

        UpdateUserStatusRequest request = new UpdateUserStatusRequest();
        request.setEnabled(false);

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                userService.updateUserStatus(1L, request, 1L));

        assertEquals("Không thể tự khóa tài khoản của chính mình", ex.getMessage());
    }

    // ─────────────────────────────────────────────
    // Case 5: Owner khóa tài khoản Admin → LỖI 403
    // ─────────────────────────────────────────────
    @Test
    @DisplayName("Case 5: Owner (rank 5) khóa tài khoản Admin (rank 6) → LỖI 403 (AccessDeniedException)")
    void ownerLocksAdmin_shouldFailWith403() {
        when(accountRepository.findById(1L)).thenReturn(Optional.of(adminAccount));
        when(accountRepository.findById(2L)).thenReturn(Optional.of(ownerAccount));

        UpdateUserStatusRequest request = new UpdateUserStatusRequest();
        request.setEnabled(false);

        AccessDeniedException ex = assertThrows(AccessDeniedException.class, () ->
                userService.updateUserStatus(1L, request, 2L));

        assertEquals("Tài khoản Owner không có quyền khóa tài khoản Admin", ex.getMessage());
    }

    // ─────────────────────────────────────────────
    // Case 6: Admin khóa tài khoản Owner → THÀNH CÔNG
    // ─────────────────────────────────────────────
    @Test
    @DisplayName("Case 6: Admin (rank 6) khóa tài khoản Owner (rank 5) → THÀNH CÔNG")
    void adminLocksOwner_shouldSucceed() {
        when(accountRepository.findById(2L)).thenReturn(Optional.of(ownerAccount));
        when(accountRepository.findById(1L)).thenReturn(Optional.of(adminAccount));

        UpdateUserStatusRequest request = new UpdateUserStatusRequest();
        request.setEnabled(false);

        assertDoesNotThrow(() -> userService.updateUserStatus(2L, request, 1L));
        assertFalse(ownerAccount.getIsActive());
        verify(accountRepository).save(ownerAccount);
    }

    // ─────────────────────────────────────────────
    // Case 7: Khóa Admin cuối cùng còn active → bị CHẶN
    // ─────────────────────────────────────────────
    @Test
    @DisplayName("Case 7: Khóa Admin cuối cùng còn active trong hệ thống → bị CHẶN (BusinessValidationException)")
    void lockLastActiveAdmin_shouldBeBlocked() {
        Account admin2 = Account.builder().id(4L).email("admin2@apms.com").isActive(true).roles(Set.of(SystemRole.SYSTEM_ADMIN)).build();
        when(accountRepository.findById(1L)).thenReturn(Optional.of(adminAccount));
        when(accountRepository.findById(4L)).thenReturn(Optional.of(admin2));
        when(accountRepository.countByRolesContainingAndIsActiveTrue(SystemRole.SYSTEM_ADMIN)).thenReturn(1L);

        UpdateUserStatusRequest request = new UpdateUserStatusRequest();
        request.setEnabled(false);

        BusinessValidationException ex = assertThrows(BusinessValidationException.class, () ->
                userService.updateUserStatus(1L, request, 4L));

        assertEquals("Không thể khóa tài khoản Admin cuối cùng còn hoạt động trong hệ thống", ex.getMessage());
    }
}
